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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.CompressionPreferences
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.data.upload.VideoUploadCompressor
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.MetadataStripConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager

private const val TAG = "CompressCloudPhoto"

/**
 * Compresses a photo or video that is already on Drive: downloads it, compresses the working copy with the
 * upload compression choices (the photo tier, the video codec and tier, whether an Ultra HDR photo may be
 * flattened), and replaces the original with the smaller copy through [CloudPhotoReplacement], which moves
 * the original to the Drive trash only once the copy is uploaded and back in every album.
 *
 * A file the compressor would not make smaller, a Motion Photo, an Ultra HDR photo the settings keep, an
 * animated or transparent image, or a clip the planner leaves alone is skipped, and nothing on Drive
 * changes. The settings' on/off switches for upload compression do not apply: asking for this is the
 * switch.
 */
@Singleton
class CompressCloudPhotoUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val exifRewriter: CloudPhotoExifRewriter,
    private val compressionStep: UploadCompressionStep,
    carryOver: CarryOverToReplacement,
    linkCheck: CloudLinkCheck,
) {
    private val replacement = CloudPhotoReplacement(context, cloudRepo, carryOver, linkCheck::isLive)

    sealed interface Result {
        data class Compressed(val savedBytes: Long, val newLinkId: String) : Result

        /** Compression does not apply to this file or would not make it smaller; Drive is unchanged. */
        data object Skipped : Result

        /** As [CloudPhotoReplacement.Result.Failed]: [copyLinkId] is a copy already on Drive, [carriedOver]
         *  says the original's labels and faces already moved to it. */
        data class Failed(
            val reason: String,
            val transient: Boolean = false,
            val copyLinkId: String? = null,
            val carriedOver: Boolean = false,
        ) : Result
    }

    suspend operator fun invoke(
        photo: CloudPhoto,
        onPhase: ((CloudSavePhase) -> Unit)? = null,
        onCompressProgress: ((Float) -> Unit)? = null,
        resumeUploadedLinkId: String? = null,
        onUploaded: (suspend (String) -> Unit)? = null,
        resumeCarriedOver: Boolean = false,
        onCarriedOver: (suspend () -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val mime = photo.mimeType.lowercase()
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) return@withContext Result.Skipped
        // A photo whose meaning lives beside its pixels is left as it is: a Live Photo or Motion Photo (its
        // video), a burst (its group), a panorama (its viewer metadata) or a RAW file (it would turn into a
        // JPEG). The Drive tag says so even where the bytes alone would not.
        if (photo.tags.any { it in KEEP_AS_IS_TAGS } || RAW_MIME.any { it in mime }) return@withContext Result.Skipped
        val userId = accountManager.getPrimaryUserId().first() ?: return@withContext Result.Failed("not signed in")
        // The download, its compressed copy and the upload's encrypted blocks have to fit with room to spare,
        // for each of the photos running at once, or the phone is left full.
        val needed = photo.sizeBytes.coerceAtLeast(0L) * 3 * PARALLEL_PHOTOS + MIN_FREE_BYTES
        if (resumeUploadedLinkId == null && context.cacheDir.usableSpace < needed) {
            Log.w(TAG, "not enough free space for ${photo.linkId}")
            // Kept in the queue for a later run rather than counted as done: room may come back.
            return@withContext Result.Failed("not enough free space", transient = true)
        }
        val prefs = context.settingsDataStore.data.first()
        // A hidden photo is hidden by its link, and the copy gets a new one, so compressing it would bring
        // it back into view. It is left as it is, whichever way it was asked for.
        if (photo.linkId in (prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet())) return@withContext Result.Skipped
        val settings = CompressionPreferences.settings(prefs).copy(photos = true, videos = true)
        onPhase?.invoke(CloudSavePhase.PREPARING)
        var originalBytes = photo.sizeBytes
        val prepare = CloudPhotoReplacement.Prepare { working ->
            originalBytes = working.length()
            val workingUri = Uri.fromFile(working).toString()
            val compressed: File?
            val producedMime: String?
            if (isVideo) {
                val attempt = compressionStep.video(
                    uri = workingUri,
                    settings = settings,
                    captureMs = photo.captureTimeMs,
                    onProgress = { p: VideoUploadCompressor.Progress -> onCompressProgress?.invoke(p.fraction) },
                )
                compressed = attempt.file
                // A transcode always writes MP4, whatever the original's container.
                producedMime = if (attempt.file != null) UploadFormatNaming.MP4 else null
            } else {
                val result = compressionStep.photo(workingUri, photo.mimeType, settings, MetadataStripConfig())
                compressed = result.file
                producedMime = result.producedMime
            }
            if (compressed == null) return@Prepare null
            // Never replace a photo with a copy that is not clearly smaller.
            if (compressed.length() <= 0L || compressed.length() >= originalBytes) {
                compressed.delete()
                return@Prepare null
            }
            val (name, uploadMime) = UploadFormatNaming.forProducedFormat(photo.displayName, photo.mimeType, producedMime)
            val compressedUri = Uri.fromFile(compressed).toString()
            val xAttr = exifRewriter.xAttrFor(compressedUri, uploadMime, photo.captureTimeMs, LocationEdit.Unchanged)
            CloudPhotoReplacement.Prepared(
                file = compressed,
                item = LocalMediaItem(
                    uri = compressedUri,
                    dateTaken = photo.captureTimeMs,
                    displayName = name,
                    mimeType = uploadMime,
                    sizeBytes = compressed.length(),
                    bucketName = null,
                    width = xAttr.displayWidth ?: 0,
                    height = xAttr.displayHeight ?: 0,
                    duration = xAttr.durationMillis ?: photo.durationMs ?: 0L,
                ),
                xAttr = xAttr,
                // The categories Drive set on the original (a selfie, a portrait) go with the copy.
                extraTags = photo.tags.filter { it in CARRIED_TAGS }.toSet(),
            )
        }
        var compressedBytes = 0L
        val tracking = CloudPhotoReplacement.Prepare { working ->
            prepare.prepare(working)?.also { compressedBytes = it.item.sizeBytes }
        }
        // The compressors only read the original, so it is read where it was downloaded, not copied first.
        val r = replacement.replace(
            userId, photo, tracking, onPhase, resumeUploadedLinkId, onUploaded,
            prepareWrites = false, resumeCarriedOver = resumeCarriedOver, onCarriedOver = onCarriedOver,
        )
        when (r) {
            is CloudPhotoReplacement.Result.Success -> Result.Compressed(
                if (compressedBytes > 0) (originalBytes - compressedBytes).coerceAtLeast(0L) else 0L,
                r.newLinkId,
            )
            CloudPhotoReplacement.Result.Skipped -> Result.Skipped
            is CloudPhotoReplacement.Result.Failed -> Result.Failed(r.reason, r.transient, r.copyLinkId, r.carriedOver)
        }
    }

    private companion object {
        /** Free space kept on top of the download and its copy. */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024

        /** Photos the compression worker runs at once, each needing its own room. */
        const val PARALLEL_PHOTOS = 2

        /** Drive PhotoTags of photos left as they are: Live Photos (3), Motion Photos (4), bursts (7),
         *  panoramas (8) and RAW (9). */
        val KEEP_AS_IS_TAGS = setOf(3, 4, 7, 8, 9)

        /** Drive PhotoTags the server set on the original that the copy is sent with: selfies (5) and
         *  portraits (6). Favourite (0) is carried by its own call; screenshots and videos are re-detected. */
        val CARRIED_TAGS = setOf(5, 6)

        /** RAW containers, which the image compressor could decode but would replace with a JPEG. */
        val RAW_MIME = listOf("dng", "raw", "x-canon", "x-nikon", "x-sony", "x-fuji", "x-panasonic", "x-olympus")
    }
}
