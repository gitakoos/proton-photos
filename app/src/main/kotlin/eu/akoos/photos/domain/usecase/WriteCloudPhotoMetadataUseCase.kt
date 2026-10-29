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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.isTransientApiError
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton


/** How the caller wants a cloud photo's GPS place changed. A capture-date-only edit passes
 *  [Unchanged] so the location block is left exactly as the file already holds it. */
sealed interface LocationEdit {

    /** The GPS block is not addressed and keeps whatever the file already holds. */
    data object Unchanged : LocationEdit

    /** The GPS block is removed. */
    data object Clear : LocationEdit

    /** The GPS block is set to [latitude] / [longitude]. */
    data class Set(val latitude: Double, val longitude: Double) : LocationEdit
}

/** The step a single cloud metadata save has reached, reported through the invoke callback so a
 *  progress surface can label where the replacement is (download and rewrite, upload, then album
 *  re-add and trash). */
enum class CloudSavePhase { PREPARING, UPLOADING, FINISHING }

/**
 * Rewrites the changed EXIF onto a downloaded original in place and reports the photo xAttr that
 * describes the result. The write is LOSSLESS: only the addressed tags change, every pixel and every
 * untouched tag stays byte-for-byte. Isolated behind an interface so the orchestration in
 * [WriteCloudPhotoMetadataUseCase] can be unit-tested with a stub, while the real Android EXIF work
 * lives in [CloudPhotoExifRewriterImpl].
 */
interface CloudPhotoExifRewriter {

    /**
     * Applies the edit to [file] in place and returns the xAttr for the corrected copy. [writeCaptureMs]
     * (when non-null) rewrites the datetime tags; [location] sets, clears, or leaves the GPS block; each
     * of [description] / [artist] / [copyright] (when non-null) writes that descriptive text tag, with an
     * empty string clearing it. [xAttrCaptureMs] is the capture time stamped into the returned xAttr (the
     * new time, or the original when only the place changed). The returned xAttr carries the ORIGINAL
     * orientation, dimensions, camera and subject-area so a correction never re-orients or re-sizes the
     * photo.
     */
    fun rewrite(
        file: File,
        mimeType: String,
        writeCaptureMs: Long?,
        xAttrCaptureMs: Long,
        location: LocationEdit,
        description: String?,
        artist: String?,
        copyright: String?,
    ): UploadXAttrMetadata

    /**
     * Builds the photo xAttr for [uri] WITHOUT touching a single byte: reads the source orientation,
     * dimensions, camera and subject-area and folds in [xAttrCaptureMs] and [location], exactly the
     * read-only half of [rewrite]. Serves the Synced path, where the device file was already
     * EXIF-edited in place by the local save, so only the xAttr for the replacement upload has to be
     * derived here. [uri] may be a content or a file uri.
     */
    fun xAttrFor(
        uri: String,
        mimeType: String,
        xAttrCaptureMs: Long,
        location: LocationEdit,
    ): UploadXAttrMetadata
}

/**
 * Changes a single CLOUD photo's capture date and/or GPS place. Proton Drive has no metadata-edit or
 * revision API, so the only way to change a cloud photo's metadata is to replace it with a corrected
 * copy: download the original bytes, rewrite only the changed EXIF tags on a working copy (losslessly,
 * never decoding or recompressing pixels), upload the corrected copy as a new link, re-add that new
 * link to every album the original belonged to, and only then trash the original.
 *
 * The ordering mirrors the editor's proven cloud-replacement path. The trash step is the one that can
 * lose data, so it is fenced hard: the original is trashed ONLY after the upload has returned a verified
 * new link AND that new link has joined every album the original was in. If any album re-add fails the
 * original is left in place, which leaves a recoverable duplicate rather than a photo silently dropped
 * from an album.
 *
 * The in-file EXIF write targets EXIF-writable images; the capture date still reaches Drive through the
 * upload item for any container, since Drive stores it in its own capture-time field.
 */
@Singleton
class WriteCloudPhotoMetadataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val exifRewriter: CloudPhotoExifRewriter,
    private val carryOver: CarryOverToReplacement,
    private val linkCheck: CloudLinkCheck,
) {
    private val replacement = CloudPhotoReplacement(context, cloudRepo, carryOver, linkCheck::isLive)

    sealed interface Result {
        data object Success : Result

        /** Neither the date nor the place was addressed, so nothing was uploaded or trashed. */
        data object NothingToDo : Result

        /** [transient] marks a failure a retry could plausibly clear (network blip, 429/5xx) so the
         *  caller can hold the edit for another attempt rather than discard it. False by default: a
         *  failure not built from a classified network error stays permanent. */
        data class Failed(val reason: String, val transient: Boolean = false) : Result
    }

    suspend operator fun invoke(
        photo: CloudPhoto,
        newCaptureMs: Long?,
        location: LocationEdit,
        description: String? = null,
        artist: String? = null,
        copyright: String? = null,
        onPhase: ((CloudSavePhase) -> Unit)? = null,
        resumeUploadedLinkId: String? = null,
        onUploaded: (suspend (String) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (newCaptureMs == null && location is LocationEdit.Unchanged &&
            description == null && artist == null && copyright == null
        ) {
            return@withContext Result.NothingToDo
        }
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext Result.Failed("not signed in")
        onPhase?.invoke(CloudSavePhase.PREPARING)

        // Only the changed EXIF tags are rewritten on the working copy, losslessly; the pixels are never
        // decoded. A place-only change keeps the original capture time, so the copy sorts where it did.
        val prepare = CloudPhotoReplacement.Prepare { file ->
            val effectiveCaptureMs = newCaptureMs ?: photo.captureTimeMs
            val xAttr = exifRewriter.rewrite(
                file = file,
                mimeType = photo.mimeType,
                writeCaptureMs = newCaptureMs,
                xAttrCaptureMs = effectiveCaptureMs,
                location = location,
                description = description,
                artist = artist,
                copyright = copyright,
            )
            // Same displayName: this is a correction, not a copy. Dims and duration come from the xAttr the
            // rewriter derived from the corrected file; the known cloud duration is the fallback.
            CloudPhotoReplacement.Prepared(
                file = file,
                item = LocalMediaItem(
                    uri = Uri.fromFile(file).toString(),
                    dateTaken = effectiveCaptureMs,
                    displayName = photo.displayName,
                    mimeType = photo.mimeType,
                    sizeBytes = file.length(),
                    bucketName = null,
                    width = xAttr.displayWidth ?: 0,
                    height = xAttr.displayHeight ?: 0,
                    duration = xAttr.durationMillis ?: photo.durationMs ?: 0L,
                ),
                xAttr = xAttr,
            )
        }
        when (val r = replacement.replace(userId, photo, prepare, onPhase, resumeUploadedLinkId, onUploaded)) {
            is CloudPhotoReplacement.Result.Success -> Result.Success
            CloudPhotoReplacement.Result.Skipped -> Result.NothingToDo
            is CloudPhotoReplacement.Result.Failed -> Result.Failed(r.reason, r.transient)
        }
    }
}
