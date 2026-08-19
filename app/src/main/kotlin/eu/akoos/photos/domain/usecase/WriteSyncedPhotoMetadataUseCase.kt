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
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WriteSyncedMetadata"

/**
 * Changes a SYNCED photo's capture date and/or GPS place. A Synced photo has BOTH a device file and a
 * cloud copy, and Proton Drive cannot overwrite a photo, so a metadata edit is a cloud REPLACEMENT of
 * the device file that the local path already EXIF-edited in place on pick: hash the corrected device
 * bytes, upload them as a new cloud link, re-add that link to every album the original belonged to,
 * re-pair the device sync row to the new link, and only then trash the old cloud copy.
 *
 * The ordering mirrors [WriteCloudPhotoMetadataUseCase] and the editor's proven cloud-replacement path.
 * The invariant across every failure or cancellation: NO duplicate cloud copy is created and NO file is
 * lost. The device file is never deleted; the old cloud copy is trashed only in the final step, after a
 * verified upload, a full album re-add, and the re-pair. On a caught upload / album failure the sync row
 * is restored to a safe SYNCED state on the original link so nothing re-uploads.
 */
@Singleton
class WriteSyncedPhotoMetadataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val syncStateRepo: SyncStateRepository,
    private val exifRewriter: CloudPhotoExifRewriter,
) {

    sealed interface Result {
        data object Success : Result

        /** Neither the date nor the place was addressed, so nothing was uploaded or trashed. */
        data object NothingToDo : Result

        data class Failed(val reason: String) : Result
    }

    suspend operator fun invoke(
        cloudPhoto: CloudPhoto,
        deviceUri: String,
        newCaptureMs: Long?,
        location: LocationEdit,
        description: String? = null,
        artist: String? = null,
        copyright: String? = null,
        onPhase: ((CloudSavePhase) -> Unit)? = null,
        resumeUploadedLinkId: String? = null,
        onUploaded: (suspend (String) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        // 1. Nothing addressed → nothing to do. A text-only edit still triggers the replacement: the
        //    device file was already EXIF-edited in place with the new text tags, so re-uploading it is
        //    what carries them to the cloud copy (the tags are not written again here).
        if (newCaptureMs == null && location is LocationEdit.Unchanged &&
            description == null && artist == null && copyright == null
        ) {
            return@withContext Result.NothingToDo
        }

        // 2. Signed-in user, the cloud copy to replace, and the device row to re-pair.
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext Result.Failed("not signed in")
        val oldLinkId = cloudPhoto.linkId

        // Resume: a prior run already uploaded the corrected device file (its link was persisted the
        // moment the upload returned). This branch never re-seeds UPLOADING, never re-uploads, and never
        // uses the fresh path's restore()-to-old logic: it finishes from the album re-add, an idempotent
        // re-pair, and the trash of the old link. Safe to re-run: the re-pair is a fixed upsert, the
        // trash of an already-trashed link is a no-op, and toAdd skips any album a prior run joined. The
        // normal backup cannot race it because UploadPendingUseCase excludes the pending row's deviceUri
        // while the row still exists.
        if (resumeUploadedLinkId != null) {
            val resumeExisting = syncStateRepo.getByUri(deviceUri)
                ?: return@withContext Result.Failed("no sync row for device file")
            onPhase?.invoke(CloudSavePhase.PREPARING)
            val resumeSha1 = computeSha1(deviceUri)
                ?: return@withContext Result.Failed("device file unreadable")
            val map = try {
                cloudRepo.getVerifiedAlbumIdsByPhoto(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext Result.Failed("could not read album membership: ${e.message}")
            }
            val albumIds = map[oldLinkId].orEmpty()
            val alreadyJoined = map[resumeUploadedLinkId].orEmpty()
            val toAdd = albumIds - alreadyJoined

            onPhase?.invoke(CloudSavePhase.FINISHING)
            val failedAlbums = mutableListOf<String>()
            for (albumId in toAdd) {
                val joined = try {
                    cloudRepo.addPhotosToAlbum(userId, albumId, listOf(resumeUploadedLinkId))
                        .succeededLinkIds.contains(resumeUploadedLinkId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "album re-add failed for $albumId: ${e.message}")
                    false
                }
                if (!joined) failedAlbums.add(albumId)
            }
            if (failedAlbums.isNotEmpty()) {
                return@withContext Result.Failed(
                    "re-add failed for ${failedAlbums.size} of ${albumIds.size} album(s); original kept",
                )
            }

            // Re-pair the row to the new link idempotently, then trash the OLD cloud copy best-effort.
            val now = System.currentTimeMillis()
            syncStateRepo.upsert(
                resumeExisting.copy(
                    cloudFileId = resumeUploadedLinkId,
                    localHash = resumeSha1,
                    status = SyncStatus.SYNCED,
                    lastSyncSuccessMs = now,
                    backedUpAtMs = now,
                ),
                userId,
            )
            syncStateRepo.clearQueuedForSynced(deviceUri)
            runCatching { cloudRepo.deleteFiles(userId, listOf(oldLinkId)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "trash of original $oldLinkId failed: ${it.message}")
                }
            return@withContext Result.Success
        }

        val existing = syncStateRepo.getByUri(deviceUri)
            ?: return@withContext Result.Failed("no sync row for device file")

        // restore() puts the row back to a safe SYNCED state on the ORIGINAL link, with the ORIGINAL
        // hash, and clears the queued flag. This is the correct failure restore because reconcile and
        // the upload selector LEAVE SYNCED rows ALONE:
        //  - ReconcileSyncStateUseCase.kt:206-210 uses the STORED localHash for any row that has one and
        //    only recomputes a hash when it is empty (post-reinstall); it NEVER re-hashes a SYNCED row's
        //    device file to detect a byte change, and LOCAL_MODIFIED is never assigned anywhere
        //    (SyncStatus.kt:29 defines it, nothing sets it).
        //  - The SYNCED-demotion pass (ReconcileSyncStateUseCase.kt:302-329) only demotes on the cloud
        //    copy going missing from the listing (→ LOCAL_ONLY) or the local file going missing
        //    (→ CLOUD_ONLY); neither fires here since the old cloud copy is never trashed on a failure
        //    and the device file always exists.
        //  - The upload selector claims only "LOCAL_ONLY AND queued" rows (SyncStateDao.kt:65-66, 157-158).
        // So restoring to SYNCED(oldLinkId) with the queue cleared means no selector ever picks the row
        // up: no re-upload, hence NO duplicate. The edit simply did not take on the cloud and stays
        // retryable. (localHash is left as the original; even if a later reconcile recomputes it from the
        // edited bytes, byId still matches oldLinkId so the row stays SYNCED and is never re-uploaded.)
        suspend fun restore() {
            syncStateRepo.upsert(existing.copy(status = SyncStatus.SYNCED), userId)
            syncStateRepo.clearQueuedForSynced(deviceUri)
        }

        // 3. Hash the device bytes now: the local path already wrote the edited EXIF in place, so this
        //    is the content that will reach Drive (its ContentHash is derived from these bytes).
        onPhase?.invoke(CloudSavePhase.PREPARING)
        val sha1 = computeSha1(deviceUri)
            ?: return@withContext Result.Failed("device file unreadable")
        // The correction inherits the original capture time when only the place changed, so the
        // corrected copy sorts exactly where the original did.
        val effectiveCaptureMs = newCaptureMs ?: cloudPhoto.captureTimeMs

        // 4. Enumerate every album the ORIGINAL belongs to before the upload, so the re-add can never
        //    miss one because of a mid-flight change. This runs before the row is marked UPLOADING and
        //    before the upload, so the verified read's throw is caught here and aborts with the sync row
        //    still in its prior SYNCED state and the original untouched: no upload, no trash, no restore
        //    needed. Refusing on an under-reported album set is what stops a silent drop from an album.
        val albumIds = try {
            cloudRepo.getVerifiedAlbumIdsByPhoto(userId)[oldLinkId].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.Failed("could not read album membership: ${e.message}")
        }

        // 5. The xAttr for the corrected copy (read-only over the already-edited device file), and the
        //    upload item carrying it. Same displayName: this is a correction, not a copy.
        val xAttr = exifRewriter.xAttrFor(deviceUri, cloudPhoto.mimeType, effectiveCaptureMs, location)
        val item = LocalMediaItem(
            uri = deviceUri,
            dateTaken = effectiveCaptureMs,
            displayName = cloudPhoto.displayName,
            mimeType = cloudPhoto.mimeType,
            sizeBytes = querySizeBytes(deviceUri),
            bucketName = null,
            width = xAttr.displayWidth ?: 0,
            height = xAttr.displayHeight ?: 0,
            duration = 0L,
        )

        // Steps 6-10 mutate the sync row and the cloud, so they run under one guard. Any cancellation
        // (the save drawer's cancel, an album-bulk abort) or unexpected failure must leave NO row stuck
        // at UPLOADING, which the process-death recovery would later re-upload as a duplicate. [repaired]
        // gates the restore: before the step-9 re-pair the row still belongs to the original link and a
        // failure restores it there; once re-paired the row already points at the live new link, so a
        // failure in the final best-effort trash must NOT revert that completed edit.
        var repaired = false
        val now = System.currentTimeMillis()
        try {
            // 6. Guard the SyncWorker race: mark the row UPLOADING so reconcile / the background sync
            //    skip it (both leave UPLOADING rows to the owner, else they would race and upload a
            //    duplicate). cloudFileId is NULLED on purpose: the process-death recovery resets only
            //    "UPLOADING AND cloudFileId IS NULL" rows (SyncStateDao.kt:166-167), so a crash anywhere
            //    between here and the re-pair self-heals to LOCAL_ONLY and the normal backup re-uploads
            //    the edited file; the old cloud copy (never trashed until the final step) is then a
            //    recoverable duplicate, never a lost file. queued=EDITOR keeps that recovered row
            //    eligible for the selector. markQueued is a separate write because upsert's domain mapper
            //    drops the queue columns.
            syncStateRepo.upsert(
                existing.copy(
                    cloudFileId = null,
                    localHash = sha1,
                    status = SyncStatus.UPLOADING,
                    lastSyncAttemptMs = now,
                ),
                userId,
            )
            syncStateRepo.markQueued(deviceUri, QueueSource.EDITOR, now)

            // 7. Upload the corrected device file as a new link. A non-cancellation failure restores the
            //    row and stops here; a cancellation rethrows to the outer guard, which restores. Either
            //    way the old cloud copy is untouched, so nothing is lost and nothing is duplicated.
            onPhase?.invoke(CloudSavePhase.UPLOADING)
            val newLinkId = try {
                cloudRepo.uploadFile(userId, item, sha1, deviceUri, xAttr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "synced upload failed for $oldLinkId: ${e.message}")
                restore()
                return@withContext Result.Failed("upload failed: ${e.message}")
            }
            if (newLinkId.isBlank()) {
                restore()
                return@withContext Result.Failed("upload returned no link id")
            }

            // Persist the new link the moment the upload is verified, BEFORE the album re-add, re-pair,
            // and trash, so a process kill from here on resumes from the recorded link (the simpler
            // resume branch above) instead of re-uploading a second copy.
            onUploaded?.invoke(newLinkId)

            // 8. Re-add the new link to EVERY album the original was in. A re-add counts only when the
            //    server confirms the join; any miss restores the row and leaves the original in place (a
            //    recoverable duplicate rather than a photo silently dropped from an album), and DOES NOT
            //    trash.
            onPhase?.invoke(CloudSavePhase.FINISHING)
            val failedAlbums = mutableListOf<String>()
            for (albumId in albumIds) {
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
            if (failedAlbums.isNotEmpty()) {
                restore()
                return@withContext Result.Failed(
                    "re-add failed for ${failedAlbums.size} of ${albumIds.size} album(s); original kept",
                )
            }

            // 9. Re-pair the device row to the new link BEFORE the trash, so a crash right after the trash
            //    still finds the row pointing at a live cloud copy. Past this the edit has landed, so a
            //    later failure must leave the row on the new link (see [repaired]).
            val pairedNow = System.currentTimeMillis()
            syncStateRepo.upsert(
                existing.copy(
                    cloudFileId = newLinkId,
                    localHash = sha1,
                    status = SyncStatus.SYNCED,
                    lastSyncSuccessMs = pairedNow,
                    backedUpAtMs = pairedNow,
                ),
                userId,
            )
            syncStateRepo.clearQueuedForSynced(deviceUri)
            repaired = true

            // 10. Trash the old cloud copy LAST, best-effort: the replacement is already live and paired,
            //     so a failed trash only leaves a recoverable duplicate.
            runCatching { cloudRepo.deleteFiles(userId, listOf(oldLinkId)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "trash of original $oldLinkId failed: ${it.message}")
                }
            Result.Success
        } catch (e: CancellationException) {
            // Cancellation anywhere in the replacement. Before the re-pair the row is stuck at UPLOADING
            // and MUST be restored, or the recovery would re-upload it as a duplicate; the restore runs
            // NonCancellable because a plain call would itself be cancelled and never run. After the
            // re-pair the row already points at the live new link, so leave it and just propagate.
            if (!repaired) withContext(NonCancellable) { restore() }
            throw e
        } catch (e: Exception) {
            // An unexpected failure at the seed or the re-pair (the upload and album steps handle their
            // own non-cancellation errors, and the trash swallows its own), reachable only before the
            // re-pair, so the row is still on the original link; restore it and report the failure.
            restore()
            return@withContext Result.Failed(e.message ?: "synced metadata write failed")
        }
    }

    /**
     * Bare SHA-1 hex of the content behind [uri], or null when it is unreadable this pass. Mirrors
     * [UploadPendingUseCase]'s hash: a null (rather than the hash of empty/partial input) is a hard
     * per-file failure so an unreadable file never uploads as a silent Drive duplicate.
     */
    private fun computeSha1(uri: String): String? {
        val digest = MessageDigest.getInstance("SHA-1")
        try {
            val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { s ->
                val buffer = ByteArray(8192)
                var read: Int
                while (s.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Byte length behind [uri] via the resolver, or 0 when it cannot be determined. */
    private fun querySizeBytes(uri: String): Long = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
            pfd.statSize.takeIf { it >= 0 } ?: 0L
        } ?: 0L
    }.getOrElse { if (it is CancellationException) throw it else 0L }
}
