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
 * metadata sections the app's own upload commits. [findUploaded] answers whether a photo is already on
 * Drive, so a failure that came after the commit is never uploaded a second time. Run only through
 * [SdkRouting] ([SdkRoute.UPLOAD]).
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

    /** The photo named [name] with content [sha1] already on Drive, live and committed, or null. */
    suspend fun findUploaded(userId: UserId, name: String, sha1: ByteArray): SdkUploadResult? =
        clientProvider.withPhotosClient(userId) { client ->
            client.findPhotoDuplicates(name) { sha1 }.firstNotNullOfOrNull { uid ->
                val node = client.getNode(uid) as? FileNode ?: return@firstNotNullOfOrNull null
                if (node.trashTime != null) return@firstNotNullOfOrNull null
                val revision = node.activeRevision?.uid as? LegacyRevisionUid ?: return@firstNotNullOfOrNull null
                SdkUploadResult((uid as? LegacyNodeUid ?: return@firstNotNullOfOrNull null).linkId, revision.revisionId)
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
