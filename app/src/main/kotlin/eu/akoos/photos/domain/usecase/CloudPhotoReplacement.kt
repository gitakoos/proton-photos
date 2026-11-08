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
import eu.akoos.photos.util.mimeToFileExtension
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId

private const val TAG = "CloudPhotoReplacement"

/**
 * Replaces one CLOUD photo with a changed copy, the only way Drive lets a photo's bytes change (it has no
 * revision API for photos): download the original, let [Prepare] turn it into the copy to upload, upload
 * that as a new link, re-add the new link to every album the original was in, carry its favourite, labels,
 * faces and search index over, and only then move the original to the Drive trash.
 *
 * The original is never written to: a preparation that writes gets a private working copy, and one that
 * only reads ([replace]'s `prepareWrites` false) reads the downloaded file. The trash is the one step that
 * can lose anything, so it is fenced hard: it runs ONLY after the upload returned a verified new link AND
 * that link joined every album the original was in, and a trash the server refuses is a failure, not a
 * success. The new link is handed to `onUploaded` the moment the upload returns, and the upload and that
 * hand-over are not cancelled halfway, so a caller that records it can resume a stopped run instead of
 * uploading a second copy. A resume first checks that the recorded copy is still on Drive; when it is not
 * (deleted or trashed meanwhile), the photo is replaced from the start rather than its original trashed,
 * unless the original's labels and faces had already moved to that copy, in which case the original is
 * left as it is and the copy is the one to restore. Only one replacement of a photo runs at a time
 * ([Claims]), so a date edit and a compression of the same photo never both upload a copy.
 *
 * Shared by the date and place edit ([WriteCloudPhotoMetadataUseCase]) and the compression of a photo
 * already on Drive ([CompressCloudPhotoUseCase]); they differ only in what [Prepare] does to the bytes.
 */
class CloudPhotoReplacement(
    private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val carryOver: CarryOverToReplacement,
    /** Whether a link is live on Drive (neither trashed nor gone). Throws when Drive cannot be asked. */
    private val linkIsLive: suspend (UserId, String) -> Boolean,
) {

    /** The copy to upload: its bytes in [file], described by [item] and [xAttr]; [extraTags] are the Drive
     *  category tags the copy is sent with, so the ones the server set on the original stay. */
    data class Prepared(
        val file: File,
        val item: LocalMediaItem,
        val xAttr: UploadXAttrMetadata,
        val extraTags: Set<Int> = emptySet(),
    )

    /** Turns the downloaded original into the copy to upload. Null leaves the photo as it is (the change
     *  does not apply to it); a thrown exception fails the replacement before anything is uploaded. A new
     *  file it writes is the caller's no longer: the replacement deletes it when it is done. */
    fun interface Prepare {
        suspend fun prepare(source: File): Prepared?
    }

    sealed interface Result {
        data class Success(val newLinkId: String) : Result

        /** [Prepare] declined, so nothing was uploaded or trashed. */
        data object Skipped : Result

        /**
         * [transient] marks a failure a retry could plausibly clear (a network blip, 429/5xx). [copyLinkId]
         * is the copy already on Drive when the failure came after the upload, null before it; [carriedOver]
         * says the original's labels, faces and favourite already moved to that copy, so the copy may no
         * longer be removed to undo the attempt.
         */
        data class Failed(
            val reason: String,
            val transient: Boolean = false,
            val copyLinkId: String? = null,
            val carriedOver: Boolean = false,
        ) : Result
    }

    suspend fun replace(
        userId: UserId,
        photo: CloudPhoto,
        prepare: Prepare,
        onPhase: ((CloudSavePhase) -> Unit)? = null,
        resumeUploadedLinkId: String? = null,
        onUploaded: (suspend (String) -> Unit)? = null,
        prepareWrites: Boolean = true,
        resumeCarriedOver: Boolean = false,
        onCarriedOver: (suspend () -> Unit)? = null,
    ): Result {
        val workDir = File(context.cacheDir, WORK_DIR).also { it.mkdirs() }
        var working: File? = null
        var prepared: File? = null
        // The download lands in the full-res cache the viewer shares. One that was not there before stays
        // only as long as this replacement needs it, and one whose photo is gone (trashed below) goes with
        // it, so a batch of thousands never fills the phone with originals.
        val cached = File(context.cacheDir, "fullres/${photo.linkId}.${mimeToFileExtension(photo.mimeType)}")
        val wasCached = cached.exists()
        var replaced = false
        var newLinkId: String? = null
        // Whether the original's labels and faces are on the copy, so it may no longer be removed to undo.
        var carried = resumeCarriedOver
        try {
            // A recorded copy is trusted only while Drive still holds it. One deleted or trashed meanwhile
            // means starting over: trashing the original against a copy that is gone would leave neither.
            var resume = resumeUploadedLinkId
            if (resume != null) {
                val live = try {
                    linkIsLive(userId, resume)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.Failed(
                        "could not check the uploaded copy: ${e.message}", transient = true, copyLinkId = resume,
                        carriedOver = carried,
                    )
                }
                if (!live && carried) {
                    return Result.Failed(
                        "the copy holding this photo's labels and faces is no longer on Drive; original kept",
                        carriedOver = true,
                    )
                }
                if (!live) {
                    Log.w(TAG, "recorded copy of ${photo.linkId} is no longer on Drive; starting over")
                    resume = null
                }
            }

            val albumIds: Set<String>
            val toAdd: Set<String>
            val copyLink: String
            if (resume == null) {
                val original = cloudRepo.downloadFullResPhoto(userId, photo)
                // A preparation that writes gets a private copy: the download cache file is shared with the
                // viewer and swept by age, so nothing here may change it in place.
                val source = if (prepareWrites) {
                    File(workDir, "cloud_${photo.linkId.take(24)}_${System.currentTimeMillis()}").also { file ->
                        working = file
                        original.inputStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                    }
                } else {
                    original
                }

                val copy = prepare.prepare(source) ?: return Result.Skipped
                if (copy.file == original) return Result.Failed("the copy is the original file; original kept")
                prepared = copy.file.takeIf { it != source }

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
                // Not cancelled halfway: a stop that lands after Drive accepted the copy but before its link
                // is recorded would leave a copy nobody knows about, and the next run would upload another.
                val (uploaded, recorded) = try {
                    withContext(NonCancellable) {
                        val link = cloudRepo.uploadFileLinked(
                            userId, copy.item, hash, copy.item.uri, copy.xAttr, extraTags = copy.extraTags,
                        )
                        // A copy whose link could not be recorded is one a later run would not know about,
                        // so it is taken back right away instead of left beside the original.
                        val ok = link.isBlank() || link == photo.linkId ||
                            runCatching { onUploaded?.invoke(link) }.isSuccess
                        if (!ok) runCatching { cloudRepo.deleteFiles(userId, listOf(link)) }
                        link to ok
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "upload failed for ${photo.linkId}: ${e.message}")
                    return Result.Failed("upload failed: ${e.message}", isTransientApiError(e))
                }
                // The copy must be a new photo: the original is trashed below.
                if (uploaded == photo.linkId) return Result.Failed("upload returned the original photo; original kept")
                if (uploaded.isBlank()) return Result.Failed("upload returned no link id")
                if (!recorded) return Result.Failed("could not record the uploaded copy; copy taken back", transient = true)
                copyLink = uploaded
                newLinkId = uploaded
                // The new link is in no album yet, so the re-add targets the original's whole set.
                toAdd = albumIds
            } else {
                // Resume: the copy is on Drive. The re-add skips the albums a prior run already joined (the
                // server rejects an already-member link per entry), while albumIds still names the original's
                // whole set for the trash guard.
                copyLink = resume
                newLinkId = resume
                val map = try {
                    cloudRepo.getVerifiedAlbumIdsByPhoto(userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result.Failed("could not read album membership: ${e.message}", isTransientApiError(e), resume, carried)
                }
                albumIds = map[photo.linkId].orEmpty()
                toAdd = albumIds - map[resume].orEmpty()
            }

            onPhase?.invoke(CloudSavePhase.FINISHING)
            // A re-add counts only when the server confirms the new link joined; a partial or thrown add is
            // a miss, and one transient miss makes the whole re-add worth retrying.
            val failedAlbums = mutableListOf<String>()
            var anyTransientAlbumMiss = false
            for (albumId in toAdd) {
                val joined = try {
                    cloudRepo.addPhotosToAlbum(userId, albumId, listOf(copyLink)).succeededLinkIds.contains(copyLink)
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
                    copyLinkId = copyLink,
                    carriedOver = carried,
                )
            }
            // Recorded together with the move, so a stop in between never forgets the copy now holds them.
            withContext(NonCancellable) {
                carryOver(userId, photo.linkId, copyLink, original = photo)
                carried = true
                onCarriedOver?.invoke()
            }
            // A refused trash leaves the original on Drive: reported, so the photo is not counted as done and
            // a later run tries the trash again.
            val trashFailed = try {
                photo.linkId in cloudRepo.deleteFiles(userId, listOf(photo.linkId)).failedLinkIds
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "trash of original ${photo.linkId} failed: ${e.message}")
                true
            }
            if (trashFailed) {
                return Result.Failed("the original could not be moved to the trash", true, copyLink, carriedOver = true)
            }
            replaced = true
            return Result.Success(copyLink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "replacement failed for ${photo.linkId}: ${e.message}")
            return Result.Failed(e.message ?: "replacement failed", isTransientApiError(e), newLinkId, carried)
        } finally {
            working?.delete()
            prepared?.delete()
            if (!wasCached || replaced) cached.delete()
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

    /** The photos being replaced right now in this process, by original link. A worker claims a photo
     *  before replacing it and leaves it for later when another worker holds it. */
    object Claims {
        private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun tryClaim(linkId: String): Boolean = held.add(linkId)

        fun release(linkId: String) {
            held.remove(linkId)
        }
    }
}
