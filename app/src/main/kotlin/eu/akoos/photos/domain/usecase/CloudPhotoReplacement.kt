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
import android.util.Log
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.isTransientApiError
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import me.proton.core.domain.entity.UserId

private const val TAG = "CloudPhotoReplacement"

/**
 * Replaces one CLOUD photo with a changed copy, the only way Drive lets a photo's bytes change (it has no
 * revision API for photos): download the original into a private working file, let [Prepare] turn it into
 * the copy to upload, upload that as a new link, re-add the new link to every album the original was in,
 * carry its favourite, labels, faces and search index over, and only then move the original to the Drive
 * trash.
 *
 * The original is never written to: the working file is a private copy, and the download cache file it
 * came from is left alone. The trash is the one step that can lose anything, so it is fenced hard: it runs
 * ONLY after the upload returned a verified new link AND that link joined every album the original was in.
 * A missed album leaves the original in place, which leaves a recoverable duplicate rather than a photo
 * dropped from an album. The new link is handed to `onUploaded` the moment the upload returns, so a caller
 * that persists it can resume a killed run from the album re-add instead of uploading a second copy.
 *
 * Shared by the date and place edit ([WriteCloudPhotoMetadataUseCase]) and the compression of a photo
 * already on Drive ([CompressCloudPhotoUseCase]); they differ only in what [Prepare] does to the bytes.
 */
class CloudPhotoReplacement(
    private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val carryOver: CarryOverToReplacement,
) {

    /** The copy to upload: its bytes in [file], described by [item] and [xAttr]. */
    data class Prepared(val file: File, val item: LocalMediaItem, val xAttr: UploadXAttrMetadata)

    /** Turns the working copy of the original into the copy to upload. Null leaves the photo as it is (the
     *  change does not apply to it); a thrown exception fails the replacement before anything is uploaded.
     *  A new file it writes is the caller's no longer: the replacement deletes it with the working copy. */
    fun interface Prepare {
        suspend fun prepare(working: File): Prepared?
    }

    sealed interface Result {
        data class Success(val newLinkId: String) : Result

        /** [Prepare] declined, so nothing was uploaded or trashed. */
        data object Skipped : Result

        /** [transient] marks a failure a retry could plausibly clear (a network blip, 429/5xx). */
        data class Failed(val reason: String, val transient: Boolean = false) : Result
    }

    suspend fun replace(
        userId: UserId,
        photo: CloudPhoto,
        prepare: Prepare,
        onPhase: ((CloudSavePhase) -> Unit)? = null,
        resumeUploadedLinkId: String? = null,
        onUploaded: (suspend (String) -> Unit)? = null,
    ): Result {
        val workDir = File(context.cacheDir, WORK_DIR).also { it.mkdirs() }
        var working: File? = null
        var prepared: File? = null
        try {
            val newLinkId: String
            val albumIds: Set<String>
            val toAdd: Set<String>
            if (resumeUploadedLinkId == null) {
                // A private copy of the downloaded original: the download cache file is shared with the
                // viewer and swept by age, so nothing here may change it in place.
                val file = File(workDir, "cloud_${photo.linkId.take(24)}_${System.currentTimeMillis()}")
                working = file
                val original = cloudRepo.downloadFullResPhoto(userId, photo)
                original.inputStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }

                val copy = prepare.prepare(file) ?: return Result.Skipped
                prepared = copy.file.takeIf { it != file }

                // Hash exactly the bytes being uploaded: Drive's ContentHash is derived from them.
                val hash = sha1(copy.file)

                // Every album the ORIGINAL is in, read before the upload so the re-add can never miss one.
                // The verified read throws rather than return a partial list, so a failed read stops the
                // replacement here, before anything is uploaded.
                albumIds = try {
                    cloudRepo.getVerifiedAlbumIdsByPhoto(userId)[photo.linkId].orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.Failed("could not read album membership: ${e.message}", isTransientApiError(e))
                }

                onPhase?.invoke(CloudSavePhase.UPLOADING)
                newLinkId = try {
                    cloudRepo.uploadFile(userId, copy.item, hash, copy.item.uri, copy.xAttr)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "upload failed for ${photo.linkId}: ${e.message}")
                    return Result.Failed("upload failed: ${e.message}", isTransientApiError(e))
                }
                // The copy must be a new photo: the original is trashed below.
                if (newLinkId == photo.linkId) return Result.Failed("upload returned the original photo; original kept")
                if (newLinkId.isBlank()) return Result.Failed("upload returned no link id")

                // Recorded before the album re-add and the trash, so a kill from here on resumes from this
                // link. The new link is in no album yet, so the re-add targets the original's whole set.
                onUploaded?.invoke(newLinkId)
                toAdd = albumIds
            } else {
                // Resume: the copy is already on Drive. The re-add skips the albums a prior run already
                // joined (the server rejects an already-member link per entry), while albumIds still names
                // the original's whole set for the trash guard.
                newLinkId = resumeUploadedLinkId
                val map = try {
                    cloudRepo.getVerifiedAlbumIdsByPhoto(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.Failed("could not read album membership: ${e.message}", isTransientApiError(e))
                }
                albumIds = map[photo.linkId].orEmpty()
                toAdd = albumIds - map[newLinkId].orEmpty()
            }

            onPhase?.invoke(CloudSavePhase.FINISHING)
            // A re-add counts only when the server confirms the new link joined; a partial or thrown add is
            // a miss, and one transient miss makes the whole re-add worth retrying.
            val failedAlbums = mutableListOf<String>()
            var anyTransientAlbumMiss = false
            for (albumId in toAdd) {
                val joined = try {
                    cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)).succeededLinkIds.contains(newLinkId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "album re-add failed for $albumId: ${e.message}")
                    anyTransientAlbumMiss = anyTransientAlbumMiss || isTransientApiError(e)
                    false
                }
                if (!joined) failedAlbums.add(albumId)
            }

            // The trash guard: a missed album leaves the original untouched and reports the partial.
            if (failedAlbums.isNotEmpty()) {
                return Result.Failed(
                    "re-add failed for ${failedAlbums.size} of ${albumIds.size} album(s); original kept",
                    transient = anyTransientAlbumMiss,
                )
            }
            carryOver(userId, photo.linkId, newLinkId, original = photo)
            runCatching { cloudRepo.deleteFiles(userId, listOf(photo.linkId)) }
                .onFailure { Log.w(TAG, "trash of original ${photo.linkId} failed: ${it.message}") }
            return Result.Success(newLinkId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "replacement failed for ${photo.linkId}: ${e.message}")
            return Result.Failed(e.message ?: "replacement failed", isTransientApiError(e))
        } finally {
            working?.delete()
            prepared?.delete()
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

    companion object {
        /** The cache folder the working copies live in, one file per photo being replaced. */
        const val WORK_DIR = "metadata_edit"
    }
}
