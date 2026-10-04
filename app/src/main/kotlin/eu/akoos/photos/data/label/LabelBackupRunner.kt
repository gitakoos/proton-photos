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

package eu.akoos.photos.data.label

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.mlsync.AddressKeyEnvelopeCrypto
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.LabelDriveFile
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveResult
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.mlsync.MlSyncScratch
import eu.akoos.photos.data.mlsync.MlSyncSignOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * The save and the restore the label sync worker runs, apart from WorkManager so the worker stays thin.
 *
 * Save seals this install's labels ([PhotoLabelRepository.snapshotPhotos] and [PhotoLabelRepository.snapshotNames])
 * into one file under the account's address key and uploads it under this install's own name, as a new revision
 * of the file it wrote before. Restore downloads the newest file of every install, opens each, and merges it into
 * the labels here ([PhotoLabelRepository.applySnapshot]). Neither creates the Labels folder unless the request
 * says the user agreed. Every Drive, storage and key failure ends as a [LabelBackupResult], never a crash, and
 * the sealed files are deleted in every case.
 */
class LabelBackupRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveStore: MlDriveStore,
    private val envelopeCrypto: AddressKeyEnvelopeCrypto,
    private val labels: PhotoLabelRepository,
    private val signOut: MlSyncSignOut,
) {

    /** Runs [request] for [userId], reporting each step through [report]. */
    suspend fun run(
        userId: UserId,
        request: LabelSyncRequest,
        report: suspend (LabelBackupProgress) -> Unit,
    ): LabelBackupOutcome = when (request) {
        is LabelSyncRequest.Save -> save(userId, request.createFolderConfirmed, report)
        is LabelSyncRequest.Restore -> restore(userId, request, report)
    }

    /** What the restore drawer shows: the saved copies of every install, newest first. See [listCopies]. */
    sealed interface Copies {
        data class Found(val copies: List<LabelSavedCopy>) : Copies

        /** Drive holds no saved labels. */
        data object Empty : Copies

        data object NotCapable : Copies

        data object Failed : Copies
    }

    /**
     * The saved copies in the Drive Labels folder, each downloaded and opened so the drawer can say when it was
     * saved, by which install, and which labels it holds with their photo counts. A copy that cannot be read
     * (damaged, another account's, from a newer version) is left out; when none can be, the answer is
     * [Copies.Failed]. The files are small, and nothing is written.
     */
    suspend fun listCopies(userId: UserId): Copies {
        val listed = when (val result = driveStore.list(userId, DriveDataFolder.LABELS)) {
            is MlDriveResult.Ok -> result.value
            MlDriveResult.NotCapable -> return Copies.NotCapable
            MlDriveResult.FolderMissing -> emptyList()
            MlDriveResult.Conflict, is MlDriveResult.Failed -> return Copies.Failed
        }
        val files = LabelDriveNaming.selectForRestore(listed)
        if (files.isEmpty()) return Copies.Empty
        val ownDeviceId = driveStore.deviceId()
        val copies = files.mapNotNull { file ->
            val read = readSnapshot(userId, file) as? LabelRead.Snapshot ?: return@mapNotNull null
            LabelSavedCopy(
                deviceId = file.deviceId,
                deviceLabel = read.value.deviceLabel,
                isThisDevice = file.deviceId == ownDeviceId,
                savedAtMillis = read.value.createdAtMillis,
                revisionUid = file.file.activeRevisionUid,
                labels = LabelSnapshotMerge.counts(read.value, ownDeviceId),
            )
        }
        return if (copies.isEmpty()) Copies.Failed else Copies.Found(copies.sortedByDescending { it.savedAtMillis })
    }

    private suspend fun save(
        userId: UserId,
        createFolder: Boolean,
        report: suspend (LabelBackupProgress) -> Unit,
    ): LabelBackupOutcome {
        fun outcome(result: LabelBackupResult, photos: Int = 0, newLabels: Int = 0) =
            LabelBackupOutcome(LabelBackupOperation.SAVE, result, photos, newLabels)
        report(LabelBackupProgress(LabelBackupStep.PREPARING))
        if (signOut.isSigningOut(userId)) return outcome(LabelBackupResult.NOT_SIGNED_IN)
        val photos = labels.snapshotPhotos(userId)
        val names = labels.snapshotNames(userId)
        if (photos.isEmpty() && names.isEmpty()) return outcome(LabelBackupResult.NOTHING_TO_SAVE)
        // Looked up before anything is sealed, so a missing folder the user has not agreed to create costs nothing.
        when (val folder = driveStore.folder(userId, createIfMissing = createFolder, area = DriveDataFolder.LABELS)) {
            is MlDriveResult.Ok -> if (folder.value == null) return outcome(LabelBackupResult.FOLDER_MISSING)
            MlDriveResult.FolderMissing -> return outcome(LabelBackupResult.FOLDER_MISSING)
            MlDriveResult.NotCapable -> return outcome(LabelBackupResult.NOT_CAPABLE)
            MlDriveResult.Conflict -> return outcome(LabelBackupResult.CONFLICT)
            is MlDriveResult.Failed -> return outcome(LabelBackupResult.FAILED).also {
                Log.w(TAG, "labels folder not ready: ${folder.cause}")
            }
        }
        val deviceId = driveStore.deviceId()
        val snapshot = LabelSnapshot(
            deviceId = deviceId,
            deviceLabel = driveStore.deviceLabel,
            createdAtMillis = System.currentTimeMillis(),
            photos = photos,
            names = names,
        )
        report(LabelBackupProgress(LabelBackupStep.ENCRYPTING))
        val scratch = withContext(Dispatchers.IO) { newScratchFile("labels-save-") }
        try {
            withContext(Dispatchers.IO) {
                envelopeCrypto.sealKeys(userId).use { keys ->
                    FileOutputStream(scratch).use { LabelSnapshotSealing.seal(snapshot, envelopeCrypto, keys, it) }
                }
            }
            report(LabelBackupProgress(LabelBackupStep.UPLOADING))
            val name = LabelDriveNaming.fileName(deviceId)
            for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
                val upload = driveStore.upload(userId, name, scratch, LabelDriveNaming.MEDIA_TYPE, DriveDataFolder.LABELS)
                when (upload) {
                    is MlDriveResult.Ok ->
                        return outcome(LabelBackupResult.SAVED, photos.count { it.labels.isNotEmpty() }, names.size)
                    MlDriveResult.NotCapable -> return outcome(LabelBackupResult.NOT_CAPABLE)
                    MlDriveResult.FolderMissing -> return outcome(LabelBackupResult.FOLDER_MISSING)
                    // The file changed between the lookup of its active revision and the upload: the retry looks afresh.
                    MlDriveResult.Conflict -> Unit
                    is MlDriveResult.Failed -> {
                        Log.w(TAG, "label upload failed: ${upload.cause}")
                        return outcome(LabelBackupResult.FAILED)
                    }
                }
            }
            return outcome(LabelBackupResult.CONFLICT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "label save failed: ${e.javaClass.simpleName}")
            return outcome(LabelBackupResult.FAILED)
        } finally {
            scratch.delete()
        }
    }

    private suspend fun restore(
        userId: UserId,
        request: LabelSyncRequest.Restore,
        report: suspend (LabelBackupProgress) -> Unit,
    ): LabelBackupOutcome {
        fun outcome(result: LabelBackupResult, photos: Int = 0, newLabels: Int = 0) =
            LabelBackupOutcome(LabelBackupOperation.RESTORE, result, photos, newLabels)
        report(LabelBackupProgress(LabelBackupStep.DOWNLOADING))
        if (signOut.isSigningOut(userId)) return outcome(LabelBackupResult.NOT_SIGNED_IN)
        val read = readSnapshot(userId, request.deviceId, request.revisionUid, sizeBytes = 0L)
        val snapshot = when (read) {
            is LabelRead.Failed -> return outcome(read.result)
            is LabelRead.Snapshot -> read.value
        }
        if (signOut.isSigningOut(userId)) return outcome(LabelBackupResult.NOT_SIGNED_IN)
        report(LabelBackupProgress(LabelBackupStep.MERGING))
        return try {
            val counts = labels.applySnapshot(userId, snapshot, driveStore.deviceId(), System.currentTimeMillis())
            outcome(LabelBackupResult.RESTORED, counts.photos, counts.names)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "label restore failed: ${e.javaClass.simpleName}")
            outcome(LabelBackupResult.FAILED)
        }
    }

    private sealed interface LabelRead {
        data class Snapshot(val value: LabelSnapshot) : LabelRead

        data class Failed(val result: LabelBackupResult) : LabelRead
    }

    private suspend fun readSnapshot(userId: UserId, file: LabelDriveFile): LabelRead =
        readSnapshot(userId, file.deviceId, file.file.activeRevisionUid, file.file.sizeBytes)

    /**
     * Downloads revision [revisionUid] and opens it, or says why it could not be read. The snapshot must name
     * the install [deviceId] its file name does. A [sizeBytes] of the Drive listing over [MAX_SNAPSHOT_BYTES] is
     * refused before the download; 0 when the size is not known.
     */
    private suspend fun readSnapshot(userId: UserId, deviceId: String, revisionUid: String, sizeBytes: Long): LabelRead {
        if (sizeBytes > MAX_SNAPSHOT_BYTES) return LabelRead.Failed(LabelBackupResult.DAMAGED)
        val scratch = withContext(Dispatchers.IO) { newScratchFile("labels-restore-") }
        try {
            when (val download = driveStore.download(userId, revisionUid, scratch)) {
                is MlDriveResult.Ok -> Unit
                MlDriveResult.NotCapable -> return LabelRead.Failed(LabelBackupResult.NOT_CAPABLE)
                is MlDriveResult.Failed -> return LabelRead.Failed(
                    if (download.cause == MlDriveResult.Failed.VERIFICATION_ISSUE) LabelBackupResult.DAMAGED
                    else LabelBackupResult.FAILED,
                )
                MlDriveResult.FolderMissing, MlDriveResult.Conflict -> return LabelRead.Failed(LabelBackupResult.FAILED)
            }
            var opened = open(userId, scratch, refreshAddresses = false)
            // A key made on another device since this one cached the address list opens only after a refresh.
            if (opened == LabelSnapshotOpened.WrongKey) opened = open(userId, scratch, refreshAddresses = true)
            return when (opened) {
                is LabelSnapshotOpened.Snapshot ->
                    if (opened.value.deviceId == deviceId) LabelRead.Snapshot(opened.value)
                    else LabelRead.Failed(LabelBackupResult.DAMAGED)
                LabelSnapshotOpened.WrongKey -> LabelRead.Failed(LabelBackupResult.WRONG_ACCOUNT)
                LabelSnapshotOpened.TooNew -> LabelRead.Failed(LabelBackupResult.TOO_NEW)
                LabelSnapshotOpened.Damaged -> LabelRead.Failed(LabelBackupResult.DAMAGED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "label restore read failed: ${e.javaClass.simpleName}")
            return LabelRead.Failed(LabelBackupResult.FAILED)
        } finally {
            scratch.delete()
        }
    }

    private suspend fun open(userId: UserId, file: File, refreshAddresses: Boolean): LabelSnapshotOpened =
        withContext(Dispatchers.IO) {
            envelopeCrypto.openKeys(userId, refreshAddresses).use { keys ->
                FileInputStream(file).use { LabelSnapshotSealing.open(it, envelopeCrypto, keys) }
            }
        }

    /** A new empty file under the app cache's ML sync scratch folder, which a sign-out sweeps. */
    private fun newScratchFile(prefix: String): File {
        val dir = File(File(context.cacheDir, MlSyncScratch.DIRECTORY), SCRATCH_DIRECTORY)
        dir.mkdirs()
        if (!dir.isDirectory) throw IOException("the scratch directory is unavailable")
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_SCRATCH_MS) it.delete() }
        return File.createTempFile(prefix, ".tmp", dir)
    }

    private companion object {
        const val TAG = "LabelBackup"
        const val SCRATCH_DIRECTORY = "labels"

        /** Upload attempts a save makes: the first, and one retry after a conflict. */
        const val MAX_UPLOAD_ATTEMPTS = 2

        /** A saved file larger than this is not read: far beyond any real label list. */
        const val MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024

        /** A scratch file this much older than a new run is a leftover of a run the process did not outlive. */
        const val STALE_SCRATCH_MS = 60 * 60 * 1000L
    }
}
