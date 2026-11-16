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

package eu.akoos.photos.data.sdk

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.entity.isUsableGpsFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.UploadAbortedException
import me.proton.drive.sdk.Uploader
import me.proton.drive.sdk.UploadController
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.LegacyRevisionUid
import me.proton.drive.sdk.entity.PhotoTag
import me.proton.drive.sdk.entity.PhotosUploaderRequest
import me.proton.drive.sdk.entity.ThumbnailType
import org.json.JSONObject
import java.nio.channels.Channels
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A photo the Drive SDK committed: its link and the revision it was committed as. */
data class SdkUploadResult(val linkId: String, val revisionId: String)

/** Everything one SDK photo upload carries; the same material the app's own upload commits. */
class SdkPhotoUpload(
    val uploadUri: String,
    val name: String,
    val mediaType: String,
    val fileSize: Long,
    val time: Instant?,
    /** The still a Live Photo's video joins, as a node uid; null for any other file. */
    val mainPhotoUid: String?,
    val tags: List<PhotoTag>,
    val additionalMetadata: Map<String, ByteArray>,
    val thumbnails: Map<ThumbnailType, ByteArray>,
    val sha1: ByteArray,
    val onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
    /**
     * The photos with this name and content already on Drive before the upload ([SdkPhotoUploadOps.findUploaded]),
     * or null when that lookup failed. A failed upload compares them with the ones after it before it drops its
     * draft, so a draft is never dropped once its commit may have gone through.
     */
    val matchingBefore: List<SdkUploadResult>? = null,
)

private const val TAG = "SdkPhotoUploadOps"

/** How long a stopped upload waits for the SDK to drop its draft. */
private const val DROP_UNFINISHED_TIMEOUT_MS = 10_000L

/** How long a failed upload waits for the lookup that tells whether its commit went through. */
private const val FAILED_UPLOAD_LOOKUP_TIMEOUT_MS = 10_000L

/** Thrown when the SDK cannot take the upload, so the app's own upload runs instead. */
class SdkUploadException(message: String) : IllegalStateException(message)

/**
 * Photo uploads through the Drive SDK's Photos client: the SDK creates the draft, encrypts and uploads the
 * blocks and commits the revision, fed the plaintext stream and the same thumbnails, SHA-1, times, tags and
 * metadata sections the app's own upload commits. [findUploaded] lists the photos already on Drive with
 * the same name and content, taken before and after an upload, so a failure that came after the commit is
 * never uploaded a second time and a photo that was there before is never taken for the new one. Run only
 * through [SdkRouting] ([SdkRoute.UPLOAD]).
 */
@Singleton
class SdkPhotoUploadOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
    @ApplicationContext private val context: Context,
) {
    suspend fun upload(userId: UserId, upload: SdkPhotoUpload): SdkUploadResult =
        clientProvider.withPhotosClient(userId) { client ->
            val uploader = client.uploader(
                PhotosUploaderRequest(
                    name = upload.name,
                    mediaType = upload.mediaType,
                    fileSize = upload.fileSize,
                    lastModificationTime = upload.time?.atOffset(ZoneOffset.UTC),
                    captureTime = upload.time,
                    mainPhotoUid = upload.mainPhotoUid,
                    tags = upload.tags,
                    overrideExistingDraftByOtherClient = false,
                    additionalMetadata = upload.additionalMetadata,
                    noWaiting = false,
                ),
            )
            // Set once a stopped upload's handles went to [dropUnfinished], which closes them after the SDK
            // has finished dropping it, never while that still runs.
            var handedOff = false
            try {
                coroutineScope {
                    val input = eu.akoos.photos.data.localcrypto.PlainContent.open(context, upload.uploadUri)
                        ?: throw SdkUploadException("the file cannot be opened")
                    input.use { stream ->
                        val controller = uploader.uploadFromStream(
                            coroutineScope = this,
                            channel = Channels.newChannel(stream),
                            thumbnails = upload.thumbnails,
                            sha1Provider = { upload.sha1 },
                        )
                        try {
                            val progress = controller.progressFlow
                                .filterNotNull()
                                .onEach { upload.onProgress?.invoke(it.bytesCompleted, it.bytesInTotal ?: upload.fileSize) }
                                .launchIn(this)
                            val stop = CompletableDeferred<Unit>()
                            val result = try {
                                // A cancel of this one photo stops the transfer through the SDK rather than
                                // the coroutine, so an upload the SDK still commits counts as committed and
                                // the steps after the commit run.
                                eu.akoos.photos.data.transfer.ItemCommitPoint.stopVia(onStop = { stop.complete(Unit) }) {
                                    val stopper = launch {
                                        stop.await()
                                        runCatching { controller.cancellationTokenSource.cancel() }
                                    }
                                    try {
                                        controller.tryResume(this)
                                        // An error the SDK can recover from pauses the upload with its draft and
                                        // the blocks already sent kept; it is resumed here rather than started
                                        // over on the app's own path.
                                        awaitResumingPauses(
                                            awaitCompletion = { controller.awaitCompletion() },
                                            isPaused = { controller.isPaused() },
                                            resume = { controller.tryResume(this) },
                                            stopped = { stop.isCompleted },
                                            waitBeforeResume = { ms -> withTimeoutOrNull(ms) { stop.await() } },
                                        )
                                    } catch (e: Throwable) {
                                        if (stop.isCompleted && e !is CancellationException) {
                                            throw CancellationException("upload cancelled by the user").apply { initCause(e) }
                                        }
                                        throw e
                                    } finally {
                                        stopper.cancel()
                                    }
                                }
                            } catch (e: CancellationException) {
                                handedOff = true
                                withContext(NonCancellable) { dropUnfinished(uploader, controller) }
                                throw e
                            } catch (e: Exception) {
                                // An aborted upload, or one still paused after its resumes, is not resumed any
                                // more (a fresh SDK upload or the app's own takes the photo over), so its draft
                                // goes too, but only once Drive shows the commit did not go through.
                                handedOff = true
                                withContext(NonCancellable) {
                                    val outcome = dropFailedSdkUpload(
                                        before = upload.matchingBefore,
                                        findAfter = { matching(client, upload.name, upload.sha1) },
                                        lookupMs = FAILED_UPLOAD_LOOKUP_TIMEOUT_MS,
                                        drop = { dropUnfinished(uploader, controller) },
                                        keep = { closeQuietly(uploader, controller) },
                                    )
                                    if (outcome != null) Log.d(TAG, "failed upload: draft kept, $outcome")
                                }
                                throw e
                            } finally {
                                progress.cancel()
                            }
                            SdkUploadResult(
                                linkId = (result.nodeUid as LegacyNodeUid).linkId,
                                revisionId = (result.revisionUid as LegacyRevisionUid).revisionId,
                            )
                        } finally {
                            if (!handedOff) controller.close()
                        }
                    }
                }
            } finally {
                if (!handedOff) uploader.close()
            }
        } ?: throw SdkUploadException("the SDK cannot run on this device")

    /**
     * A stopped upload is never resumed, so it is cancelled and disposed the way Drive's own app drops an
     * upload, which deletes the draft it left on Drive unless the commit already went through, and only then
     * are its handles closed ([dropStoppedSdkUpload]). The SDK names no draft before the commit, so a draft
     * it could not drop cannot be queued for a later delete like the app's own; the SDK deletes a draft of
     * this client under the same name when that photo is uploaded again.
     */
    private suspend fun dropUnfinished(uploader: Uploader, controller: UploadController) {
        val outcome = dropStoppedSdkUpload(
            cancelAndDispose = {
                controller.cancel()
                controller.dispose()
                uploader.cancel()
            },
            close = {
                runCatching { controller.close() }
                runCatching { uploader.close() }
            },
            launchHolding = { work -> clientProvider.launchHoldingClient(work) },
            waitMs = DROP_UNFINISHED_TIMEOUT_MS,
        )
        Log.d(
            TAG,
            when (outcome) {
                StoppedUploadDrop.DROPPED -> "stopped upload dropped with its draft"
                StoppedUploadDrop.FAILED -> "stopped upload: dropping its draft failed"
                StoppedUploadDrop.STILL_RUNNING -> "stopped upload: still dropping its draft in the background"
            },
        )
    }

    /** Frees a failed upload's handles when its draft stays. */
    private fun closeQuietly(uploader: Uploader, controller: UploadController) {
        runCatching { controller.close() }
        runCatching { uploader.close() }
    }

    /** The photos named [name] with content [sha1] on Drive, live and committed, by link id. */
    suspend fun findUploaded(userId: UserId, name: String, sha1: ByteArray): List<SdkUploadResult> =
        clientProvider.withPhotosClient(userId) { client -> matching(client, name, sha1) }
            ?: throw SdkUploadException("the SDK cannot run on this device")

    private suspend fun matching(client: ProtonPhotosClient, name: String, sha1: ByteArray): List<SdkUploadResult> =
        client.findPhotoDuplicates(name) { sha1 }.mapNotNull { uid ->
            val node = client.getNode(uid) as? FileNode ?: return@mapNotNull null
            if (node.trashTime != null) return@mapNotNull null
            val revision = node.activeRevision?.uid as? LegacyRevisionUid ?: return@mapNotNull null
            SdkUploadResult((uid as? LegacyNodeUid ?: return@mapNotNull null).linkId, revision.revisionId)
        }
}

/** How [dropStoppedSdkUpload] ended within its wait. */
internal enum class StoppedUploadDrop { DROPPED, FAILED, STILL_RUNNING }

/**
 * Drops a stopped SDK upload: [cancelAndDispose] (the SDK cancels the transfer and deletes its draft), then
 * [close] (frees the native handles), always in that order, so a handle is never freed while the SDK still
 * works on it. The two run in the background through [launchHolding] (which keeps the SDK client open
 * meanwhile), and the caller waits at most [waitMs]: past it they keep running and the answer is
 * [StoppedUploadDrop.STILL_RUNNING]. When nothing can be started in the background they run here, to the end.
 */
internal suspend fun dropStoppedSdkUpload(
    cancelAndDispose: suspend () -> Unit,
    close: () -> Unit,
    launchHolding: suspend (suspend () -> Unit) -> Job?,
    waitMs: Long,
): StoppedUploadDrop {
    val dropped = CompletableDeferred<Boolean>()
    val work: suspend () -> Unit = {
        try {
            dropped.complete(runCatching { cancelAndDispose() }.isSuccess)
        } finally {
            close()
        }
    }
    if (launchHolding(work) == null) work()
    return when (withTimeoutOrNull(waitMs) { dropped.await() }) {
        true -> StoppedUploadDrop.DROPPED
        false -> StoppedUploadDrop.FAILED
        null -> StoppedUploadDrop.STILL_RUNNING
    }
}

/**
 * Drops a failed SDK upload's draft through [drop] only when the photos matching it on Drive, [before] and
 * after it ([findAfter], given at most [lookupMs]), show nothing committed ([sdkUploadAfterFailure]). A commit
 * that went through, or one that cannot be told, keeps the draft and only frees the handles through [keep]:
 * dropping it then could remove the photo itself. Null when it was dropped, else why it was kept.
 */
internal suspend fun dropFailedSdkUpload(
    before: List<SdkUploadResult>?,
    findAfter: suspend () -> List<SdkUploadResult>,
    lookupMs: Long,
    drop: suspend () -> Unit,
    keep: () -> Unit,
): SdkUploadAfterFailure? {
    val after = withTimeoutOrNull(lookupMs) { runCatching { findAfter() }.getOrNull() }
    val outcome = sdkUploadAfterFailure(before, after)
    if (outcome == SdkUploadAfterFailure.NothingCommitted) {
        drop()
        return null
    }
    keep()
    return outcome
}

/** What a failed SDK upload left on Drive, read from the matching photos before and after it. */
internal sealed interface SdkUploadAfterFailure {
    /** The upload committed after all; this is the photo it made. */
    data class Committed(val photo: SdkUploadResult) : SdkUploadAfterFailure

    /** Nothing new is on Drive, so the app's own upload can run. */
    data object NothingCommitted : SdkUploadAfterFailure

    /** Whether the upload committed cannot be told, so nothing may upload it again now. */
    data object Unknown : SdkUploadAfterFailure
}

/**
 * Reads [before] and [after] (the matching photos on Drive, null when that lookup failed). Only a photo
 * missing from [before] can be the one this upload made, so a photo that was already there, such as the
 * original a metadata correction re-uploads under the same name and content, is never taken for it.
 */
internal fun sdkUploadAfterFailure(
    before: List<SdkUploadResult>?,
    after: List<SdkUploadResult>?,
): SdkUploadAfterFailure {
    if (after == null) return SdkUploadAfterFailure.Unknown
    if (before == null) {
        return if (after.isEmpty()) SdkUploadAfterFailure.NothingCommitted else SdkUploadAfterFailure.Unknown
    }
    val known = before.mapTo(HashSet()) { it.linkId }
    val fresh = after.filter { it.linkId !in known }
    return when (fresh.size) {
        0 -> SdkUploadAfterFailure.NothingCommitted
        1 -> SdkUploadAfterFailure.Committed(fresh.single())
        else -> SdkUploadAfterFailure.Unknown
    }
}

/** How often one SDK upload is resumed after the SDK paused it on an error it can recover from. */
internal const val MAX_SDK_UPLOAD_RESUMES = 3

/** The wait before the [attempt]th resume of a paused SDK upload (1-based). */
internal fun sdkResumeBackoffMs(attempt: Int): Long = when (attempt) {
    1 -> 2_000L
    2 -> 5_000L
    else -> 10_000L
}

/**
 * Waits for an SDK upload through [awaitCompletion]. The SDK pauses an upload that hit an error it can recover
 * from (a dropped connection, a 5xx), keeping its draft and the blocks already sent, and [awaitCompletion] then
 * throws that error with the upload [isPaused]; such an upload is resumed through [resume] after
 * [waitBeforeResume] (which returns early once the upload is [stopped]), at most [maxResumes] times. An upload
 * the SDK aborted ([UploadAbortedException]) cannot be resumed, and a stopped one is never resumed: their error
 * is thrown, as is the last one once the resumes are used up. Cancellation passes through.
 */
internal suspend fun <T> awaitResumingPauses(
    awaitCompletion: suspend () -> T,
    isPaused: suspend () -> Boolean,
    resume: suspend () -> Unit,
    stopped: () -> Boolean,
    waitBeforeResume: suspend (Long) -> Unit,
    maxResumes: Int = MAX_SDK_UPLOAD_RESUMES,
    backoffMs: (Int) -> Long = ::sdkResumeBackoffMs,
): T {
    var resumes = 0
    while (true) {
        try {
            return awaitCompletion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadAbortedException) {
            throw e
        } catch (e: Exception) {
            if (stopped() || resumes >= maxResumes) throw e
            val paused = try {
                isPaused()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                false
            }
            if (!paused) throw e
            resumes++
            runCatching { Log.d(TAG, "upload paused on ${e.javaClass.simpleName}, resume $resumes of $maxResumes") }
            waitBeforeResume(backoffMs(resumes))
            if (stopped()) throw e
            resume()
        }
    }
}

/** How often an SDK upload the SDK aborted is started again before the app's own upload takes over. */
internal const val MAX_SDK_UPLOAD_ABORT_RETRIES = 1

/** The wait before an aborted SDK upload is started again. */
internal const val SDK_ABORT_RETRY_DELAY_MS = 2_000L

/**
 * Runs [upload] and reads what a failure left on Drive through [afterFailure] (built from the photos matching it
 * before the first attempt, so a commit of any attempt shows up as new). A committed photo is the result, an
 * outcome that cannot be told throws [SdkOutcomeUnknownException], and with nothing committed an upload the SDK
 * aborted ([UploadAbortedException], such as a block the storage refused with a 400) starts again after
 * [SDK_ABORT_RETRY_DELAY_MS], at most [maxRetries] times. Any other failure, or one past the retries, is thrown
 * for the app's own upload to take over. Cancellation passes through, also during the wait.
 */
internal suspend fun uploadRetryingAbort(
    upload: suspend () -> SdkUploadResult,
    afterFailure: suspend () -> SdkUploadAfterFailure,
    maxRetries: Int = MAX_SDK_UPLOAD_ABORT_RETRIES,
): SdkUploadResult {
    var retries = 0
    while (true) {
        try {
            return upload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            when (val outcome = afterFailure()) {
                is SdkUploadAfterFailure.Committed -> return outcome.photo
                SdkUploadAfterFailure.Unknown -> throw SdkOutcomeUnknownException("upload outcome unknown", e)
                SdkUploadAfterFailure.NothingCommitted -> {
                    if (e !is UploadAbortedException || retries >= maxRetries) throw e
                    retries++
                    runCatching { Log.d(TAG, "upload aborted with nothing committed, starting it again ($retries of $maxRetries)") }
                    delay(SDK_ABORT_RETRY_DELAY_MS)
                }
            }
        }
    }
}

/** [hex] as bytes: a lowercase SHA-1 hex string. */
internal fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
    ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
}

/**
 * The metadata sections the app's own upload writes into a photo's extended attributes, for the SDK to
 * commit beside the Common section it builds itself: Media always (display width and height, duration in
 * seconds), Location only for a usable GPS fix, Camera only when something is known. Same keys and values.
 */
internal fun sdkAdditionalMetadata(
    width: Int,
    height: Int,
    durationMillis: Long,
    latitude: Double?,
    longitude: Double?,
    cameraCaptureTimeIso: String?,
    cameraDevice: String?,
    cameraOrientation: Int?,
    subjectCoordinates: IntArray?,
): Map<String, ByteArray> {
    val sections = LinkedHashMap<String, ByteArray>()
    val media = JSONObject().put("Width", width).put("Height", height)
    if (durationMillis == 0L) media.put("Duration", 0) else media.put("Duration", String.format(Locale.US, "%.3f", durationMillis / 1000.0).toDouble())
    sections["Media"] = media.toString().toByteArray()
    if (latitude != null && longitude != null && isUsableGpsFix(latitude, longitude)) {
        sections["Location"] = JSONObject().put("Latitude", latitude).put("Longitude", longitude).toString().toByteArray()
    }
    val rect = subjectCoordinates?.takeIf { it.size == 4 }
    if (cameraCaptureTimeIso != null || cameraDevice != null || cameraOrientation != null || rect != null) {
        val camera = JSONObject()
        cameraCaptureTimeIso?.let { camera.put("CaptureTime", it) }
        cameraDevice?.let { camera.put("Device", it) }
        cameraOrientation?.let { camera.put("Orientation", it) }
        rect?.let {
            camera.put("SubjectCoordinates", JSONObject().put("Top", it[0]).put("Left", it[1]).put("Bottom", it[2]).put("Right", it[3]))
        }
        sections["Camera"] = camera.toString().toByteArray()
    }
    return sections
}
