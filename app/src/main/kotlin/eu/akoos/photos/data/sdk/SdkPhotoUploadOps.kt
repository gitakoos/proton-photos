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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.entity.isUsableGpsFix
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.LegacyRevisionUid
import me.proton.drive.sdk.entity.PhotoTag
import me.proton.drive.sdk.entity.PhotosUploaderRequest
import me.proton.drive.sdk.entity.ThumbnailType
import org.json.JSONObject
import java.nio.channels.Channels
import java.time.Instant
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
)

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
                    lastModificationTime = upload.time,
                    captureTime = upload.time,
                    mainPhotoUid = upload.mainPhotoUid,
                    tags = upload.tags,
                    overrideExistingDraftByOtherClient = false,
                    additionalMetadata = upload.additionalMetadata,
                    noWaiting = false,
                ),
            )
            try {
                coroutineScope {
                    val input = context.contentResolver.openInputStream(Uri.parse(upload.uploadUri))
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
                            controller.tryResume(this)
                            val result = try {
                                controller.awaitCompletion()
                            } finally {
                                progress.cancel()
                            }
                            SdkUploadResult(
                                linkId = (result.nodeUid as LegacyNodeUid).linkId,
                                revisionId = (result.revisionUid as LegacyRevisionUid).revisionId,
                            )
                        } finally {
                            controller.close()
                        }
                    }
                }
            } finally {
                uploader.close()
            }
        } ?: throw SdkUploadException("the SDK cannot run on this device")

    /** The photos named [name] with content [sha1] on Drive, live and committed, by link id. */
    suspend fun findUploaded(userId: UserId, name: String, sha1: ByteArray): List<SdkUploadResult> =
        clientProvider.withPhotosClient(userId) { client ->
            client.findPhotoDuplicates(name) { sha1 }.mapNotNull { uid ->
                val node = client.getNode(uid) as? FileNode ?: return@mapNotNull null
                if (node.trashTime != null) return@mapNotNull null
                val revision = node.activeRevision?.uid as? LegacyRevisionUid ?: return@mapNotNull null
                SdkUploadResult((uid as? LegacyNodeUid ?: return@mapNotNull null).linkId, revision.revisionId)
            }
        } ?: throw SdkUploadException("the SDK cannot run on this device")
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
