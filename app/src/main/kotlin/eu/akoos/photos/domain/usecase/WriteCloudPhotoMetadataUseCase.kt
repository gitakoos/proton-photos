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
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WriteCloudMetadata"

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
) {

    sealed interface Result {
        data object Success : Result

        /** Neither the date nor the place was addressed, so nothing was uploaded or trashed. */
        data object NothingToDo : Result

        data class Failed(val reason: String) : Result
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

        // On a resume the corrected copy is already uploaded (its link was persisted the moment the
        // upload returned), so there is nothing to download, rewrite, hash, or upload: this run finishes
        // from the album re-add and the trash using the recorded link. working stays null so the finally
        // has nothing to clean up.
        val workDir = File(context.cacheDir, "metadata_edit").also { it.mkdirs() }
        var working: File? = null
        try {
            val newLinkId: String
            val albumIds: Set<String>
            val toAdd: Set<String>
            if (resumeUploadedLinkId == null) {
                // Copy the downloaded original's bytes into a private working file. The download cache
                // file is shared with the viewer and TTL-swept, so the lossless EXIF rewrite must never
                // mutate it in place.
                val file = File(workDir, "cloud_${photo.linkId.take(24)}_${System.currentTimeMillis()}")
                working = file
                val original = cloudRepo.downloadFullResPhoto(userId, photo)
                original.inputStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }

                // The correction inherits the original capture time when only the place changed, so the
                // corrected copy sorts exactly where the original did.
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

                // Same displayName: this is a correction, not a copy, and the original is trashed right
                // after a verified upload so the same-name window is brief.
                val item = LocalMediaItem(
                    uri = Uri.fromFile(file).toString(),
                    dateTaken = effectiveCaptureMs,
                    displayName = photo.displayName,
                    mimeType = photo.mimeType,
                    sizeBytes = file.length(),
                    bucketName = null,
                    width = xAttr.displayWidth ?: 0,
                    height = xAttr.displayHeight ?: 0,
                    duration = 0L,
                )

                // Hash exactly the bytes being uploaded, AFTER the EXIF write, because Drive's
                // ContentHash is derived from these bytes.
                val hash = sha1(file)

                // Enumerate every album the ORIGINAL belongs to before the upload, so the re-add below
                // can never miss one because of a mid-flight change. The verified read throws rather than
                // return a silent partial, so a transient album-list failure fails the save here (before
                // any upload) instead of trashing the original against an under-reported album set.
                albumIds = try {
                    cloudRepo.getVerifiedAlbumIdsByPhoto(userId)[photo.linkId].orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext Result.Failed("could not read album membership: ${e.message}")
                }

                onPhase?.invoke(CloudSavePhase.UPLOADING)
                newLinkId = try {
                    cloudRepo.uploadFile(userId, item, hash, item.uri, xAttr)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "upload failed for ${photo.linkId}: ${e.message}")
                    return@withContext Result.Failed("upload failed: ${e.message}")
                }
                if (newLinkId.isBlank()) return@withContext Result.Failed("upload returned no link id")

                // Persist the new link the moment the upload is verified, BEFORE any album re-add or
                // trash, so a process kill from here on resumes from the recorded link instead of
                // re-uploading a second copy. The just-uploaded link is in no album yet, so re-add
                // targets the full original set.
                onUploaded?.invoke(newLinkId)
                toAdd = albumIds
            } else {
                // Resume: the recorded link already exists. Read the album map once so the re-add can
                // skip any album a prior run already joined (the server treats an already-member link as
                // a per-entry rejection), while albumIds still names the full original set for the trash
                // guard's accounting.
                newLinkId = resumeUploadedLinkId
                val map = try {
                    cloudRepo.getVerifiedAlbumIdsByPhoto(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext Result.Failed("could not read album membership: ${e.message}")
                }
                albumIds = map[photo.linkId].orEmpty()
                val alreadyJoined = map[newLinkId].orEmpty()
                toAdd = albumIds - alreadyJoined
            }

            onPhase?.invoke(CloudSavePhase.FINISHING)
            // Re-add the new link to every album that still needs it. A re-add counts only when the
            // server confirms the new link joined; a crypto-partial or thrown add is a miss.
            val failedAlbums = mutableListOf<String>()
            for (albumId in toAdd) {
                val joined = try {
                    cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId))
                        .succeededLinkIds.contains(newLinkId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "album re-add failed for $albumId: ${e.message}")
                    false
                }
                if (!joined) failedAlbums.add(albumId)
            }

            // The trash guard: only reached once the upload is verified. If any album re-add missed,
            // leave the original completely untouched and report the partial so no album loses the
            // photo. Otherwise trash the original last (best-effort, like the editor's path).
            if (failedAlbums.isNotEmpty()) {
                return@withContext Result.Failed(
                    "re-add failed for ${failedAlbums.size} of ${albumIds.size} album(s); original kept",
                )
            }
            runCatching { cloudRepo.deleteFiles(userId, listOf(photo.linkId)) }
                .onFailure { Log.w(TAG, "trash of original ${photo.linkId} failed: ${it.message}") }
            Result.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "metadata write failed for ${photo.linkId}: ${e.message}")
            Result.Failed(e.message ?: "metadata write failed")
        } finally {
            working?.delete()
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
