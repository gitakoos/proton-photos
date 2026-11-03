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

package eu.akoos.photos.data.sharedbackup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.DriveCopyAppliedDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.entity.DriveCopyAppliedEntity
import eu.akoos.photos.data.hidden.HiddenCopyMerge
import eu.akoos.photos.data.hidden.HiddenSnapshot
import eu.akoos.photos.data.hidden.HiddenSnapshotCodec
import eu.akoos.photos.data.hidden.HiddenSnapshotOpened
import eu.akoos.photos.data.hidden.HiddenSnapshotSealing
import eu.akoos.photos.data.hidden.HiddenSyncStore
import eu.akoos.photos.data.label.LabelCopyMerge
import eu.akoos.photos.data.label.LabelSnapshot
import eu.akoos.photos.data.label.LabelSnapshotCodec
import eu.akoos.photos.data.label.LabelSnapshotOpened
import eu.akoos.photos.data.label.LabelSnapshotSealing
import eu.akoos.photos.data.label.PhotoLabelRepository
import eu.akoos.photos.data.mlsync.AddressKeyEnvelopeCrypto
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.HiddenDriveNaming
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFile
import eu.akoos.photos.data.mlsync.MlSyncPartStatus
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveResult
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.mlsync.MlRestoreFile
import eu.akoos.photos.data.mlsync.MlSyncScratch
import eu.akoos.photos.util.SyncDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/** The three Drive folders of the shared backup as one round lists them. */
data class SharedBackupListing(
    val ml: List<MlDriveFile>,
    val labels: List<MlDriveFile>,
    val hidden: List<MlDriveFile>,
)

/** One install's copies on Drive, as the list of the backups shows them: when it saved last, and its files. */
data class DriveDeviceCopies(val deviceId: String, val name: String?, val lastSavedMs: Long, val nodeUids: List<String>)

/**
 * The copies in [listing], by the install that wrote them: every file of the three folders whose name carries an
 * install id, newest install first. Pure, so it is unit-tested directly.
 */
internal fun copiesByDevice(listing: SharedBackupListing, names: Map<String, String>): List<DriveDeviceCopies> {
    val files = listing.ml.mapNotNull { f -> MlDriveNaming.parse(f.name)?.let { SharedBackupCopy(it.deviceId, f) } } +
        listing.labels.mapNotNull { f -> LabelDriveNaming.parse(f.name)?.let { SharedBackupCopy(it, f) } } +
        listing.hidden.mapNotNull { f -> HiddenDriveNaming.parse(f.name)?.let { SharedBackupCopy(it, f) } }
    return files.groupBy { it.deviceId }
        .map { (id, copies) ->
            DriveDeviceCopies(id, names[id], copies.maxOf { it.madeAtMs }, copies.map { it.file.nodeUid })
        }
        .sortedByDescending { it.lastSavedMs }
}

/** One other install's file a round takes in, with the time the copy carries for the "taken in" record. */
data class SharedBackupCopy(val deviceId: String, val file: MlDriveFile) {
    /** When the writing install made the copy, as far as Drive tells before the download. */
    val madeAtMs: Long
        get() = file.claimedModifiedAtMs ?: file.serverModifiedAtMs
}

/**
 * Each other install's newest copy in [files] (its install read by [deviceIdOf]) that is newer than the copy of that
 * install taken in last ([applied]); never [ownDeviceId]'s own. Pure, so it is unit-tested directly.
 */
internal fun newerCopies(
    files: List<MlDriveFile>,
    deviceIdOf: (String) -> String?,
    ownDeviceId: String,
    applied: Map<String, Long>,
): List<SharedBackupCopy> =
    files.mapNotNull { file -> deviceIdOf(file.name)?.let { SharedBackupCopy(it, file) } }
        .filter { it.deviceId != ownDeviceId }
        .groupBy { it.deviceId }
        .map { (_, copies) -> copies.maxWith(compareBy<SharedBackupCopy> { it.madeAtMs }.thenBy { it.file.nodeUid }) }
        .filter { (applied[it.deviceId] ?: -1L) < it.madeAtMs }

/**
 * The parts of a shared-backup round that are not the machine-learning import: listing the three Drive folders,
 * keeping which copy of each other install was taken in last, and taking in and writing the labels and the hidden
 * list. The machine-learning files go through the same download and import a restore uses, run by the ML sync
 * worker, which drives the whole round.
 */
class SharedBackupRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveStore: MlDriveStore,
    private val envelopeCrypto: AddressKeyEnvelopeCrypto,
    private val labels: PhotoLabelRepository,
    private val hidden: HiddenSyncStore,
    private val appliedDao: DriveCopyAppliedDao,
    private val store: SharedBackupStore,
    private val faceScanDao: FaceScanDao,
    private val imageEmbeddingDao: ImageEmbeddingDao,
) {

    /** The three folders' files, or null when any listing failed: a round that cannot read writes nothing. */
    suspend fun list(userId: UserId): SharedBackupListing? {
        val ml = listed(userId, DriveDataFolder.ML) ?: return null
        val labels = listed(userId, DriveDataFolder.LABELS) ?: return null
        val hidden = listed(userId, DriveDataFolder.HIDDEN) ?: return null
        return SharedBackupListing(ml, labels, hidden)
    }

    private suspend fun listed(userId: UserId, area: DriveDataFolder): List<MlDriveFile>? =
        when (val result = driveStore.list(userId, area)) {
            is MlDriveResult.Ok -> result.value
            MlDriveResult.FolderMissing -> emptyList()
            MlDriveResult.NotCapable, MlDriveResult.Conflict, is MlDriveResult.Failed -> {
                SyncDiagnostics.log("shared backup: listing ${area.name} failed: ${causeOf(result)}")
                null
            }
        }

    /** When each other install's copy of [kind] that was taken in last was made. */
    suspend fun applied(userId: UserId, kind: SharedBackupKind): Map<String, Long> =
        appliedDao.forKind(userId.id, kind.key).associate { it.deviceId to it.createdAt }

    /** Records that install [deviceId]'s copy of [kind], made at [madeAtMs], was taken in. */
    suspend fun markApplied(userId: UserId, kind: SharedBackupKind, deviceId: String, madeAtMs: Long) {
        appliedDao.upsert(listOf(DriveCopyAppliedEntity(userId.id, kind.key, deviceId, madeAtMs)))
    }

    /** Forgets which copies of [kind] were taken in, so the next round takes every install's newest one again. */
    suspend fun forgetApplied(userId: UserId, kind: SharedBackupKind) {
        appliedDao.deleteKind(userId.id, kind.key)
    }

    /**
     * The other installs' face and search files newer than the copy of each taken in last, as restore files: each
     * install's newest file of each kind, never this install's own. A kind this device holds nothing of (its face
     * data deleted, wiped by a model change, or its search index reset) takes every other install's newest file
     * again, whatever was taken in before: filling gaps twice changes nothing. So does every kind when [retake], the
     * user's "Load from Drive". Not so the faces once they were deleted everywhere ([facesWiped]): a copy from before
     * the delete is turned away as it is read, and one turned away once is not fetched again until it changes.
     */
    suspend fun mlFilesToTake(
        userId: UserId,
        listing: SharedBackupListing,
        ownDeviceId: String,
        retake: Boolean = false,
        facesWiped: Boolean = false,
    ): List<MlRestoreFile> {
        val holdsFaces = facesWiped || faceScanDao.hasAnyForUser(userId.id)
        val faces = if (!retake && holdsFaces) applied(userId, SharedBackupKind.FACES) else emptyMap()
        val search = if (!retake && imageEmbeddingDao.countForUser(userId.id) > 0) applied(userId, SharedBackupKind.SEARCH) else emptyMap()
        return MlDriveNaming.selectForImport(listing.ml, ownDeviceId).mapNotNull { pick ->
            val taken = if (pick.kind == eu.akoos.photos.data.mlsync.MlDriveFileKind.FACES) faces else search
            val madeAt = SharedBackupCopy(pick.deviceId, pick.file).madeAtMs
            if ((taken[pick.deviceId] ?: -1L) >= madeAt) return@mapNotNull null
            MlRestoreFile(pick.kind, pick.file.activeRevisionUid, madeAt, pick.deviceId)
        }
    }

    /**
     * Takes the other installs' label copies newer than the ones taken in last into this device's labels. False when
     * a copy could not be read, so the round does not write this device's labels over an unread copy. [retake] takes
     * every other install's newest copy in again, even one taken in before.
     */
    suspend fun takeInLabels(userId: UserId, listing: SharedBackupListing, ownDeviceId: String, retake: Boolean = false): Boolean {
        val applied = if (retake) emptyMap() else applied(userId, SharedBackupKind.LABELS)
        val copies = newerCopies(listing.labels, LabelDriveNaming::parse, ownDeviceId, applied)
        if (copies.isEmpty()) return true
        val read = ArrayList<Pair<SharedBackupCopy, LabelSnapshot>>()
        for (copy in copies) {
            when (val got = readLabels(userId, copy)) {
                is CopyRead.Read -> read += copy to got.value
                CopyRead.PassOver -> markApplied(userId, SharedBackupKind.LABELS, copy.deviceId, copy.madeAtMs)
                CopyRead.Retry -> return false
            }
        }
        store.rememberDeviceNames(read.associate { (copy, snapshot) -> copy.deviceId to snapshot.deviceLabel }.filterValues { it.isNotBlank() })
        val before = labels.loadSyncState(userId)
        val after = before.deepCopy()
        if (retake) after.applied.clear()
        after.applied.putAll(applied)
        for ((_, snapshot) in read) LabelCopyMerge.takeIn(after, snapshot, ownDeviceId)
        val changed = labels.saveSyncState(userId, before, after)
        for ((copy, snapshot) in read) {
            markApplied(userId, SharedBackupKind.LABELS, copy.deviceId, maxOf(copy.madeAtMs, snapshot.createdAtMillis))
        }
        if (changed > 0) Log.i(TAG, "labels taken in: $changed photo(s) changed")
        return true
    }

    /**
     * Takes the other installs' hidden lists newer than the ones taken in last into this device's hidden photos and
     * albums. False when a copy could not be read. [retake] takes every other install's newest list in again.
     */
    suspend fun takeInHidden(userId: UserId, listing: SharedBackupListing, ownDeviceId: String, retake: Boolean = false): Boolean {
        val applied = if (retake) emptyMap() else applied(userId, SharedBackupKind.HIDDEN)
        val copies = newerCopies(listing.hidden, HiddenDriveNaming::parse, ownDeviceId, applied)
        if (copies.isEmpty()) return true
        val read = ArrayList<Pair<SharedBackupCopy, HiddenSnapshot>>()
        for (copy in copies) {
            when (val got = readHidden(userId, copy)) {
                is CopyRead.Read -> read += copy to got.value
                CopyRead.PassOver -> markApplied(userId, SharedBackupKind.HIDDEN, copy.deviceId, copy.madeAtMs)
                CopyRead.Retry -> return false
            }
        }
        store.rememberDeviceNames(read.associate { (copy, snapshot) -> copy.deviceId to snapshot.deviceLabel }.filterValues { it.isNotBlank() })
        val state = hidden.load()
        var changes = HiddenCopyMerge.Changes()
        for ((_, snapshot) in read) changes += HiddenCopyMerge.takeIn(state, snapshot, ownDeviceId)
        val waiting = hidden.apply(changes)
        for ((copy, snapshot) in read) {
            markApplied(userId, SharedBackupKind.HIDDEN, copy.deviceId, maxOf(copy.madeAtMs, snapshot.createdAtMillis))
        }
        if (!changes.isEmpty) Log.i(TAG, "hidden list taken in, $waiting photo(s) wait for the vault")
        return true
    }

    /** Writes this device's labels to Drive unless the copy there already holds them. Null when the write failed. */
    suspend fun saveLabels(userId: UserId, ownDeviceId: String, nowMs: Long): Boolean? {
        val copy = LabelCopyMerge.forDrive(labels.loadSyncState(userId), ownDeviceId, driveStore.deviceLabel, nowMs)
        if (copy.photos.isEmpty() && copy.names.isEmpty() && copy.deleted.isEmpty()) return false
        val same = LabelSnapshotCodec.encode(copy.copy(createdAtMillis = 0L))
        if (store.unchanged(SharedBackupKind.LABELS, same)) return false
        val sealed = seal(userId, nowMs) { keys, out -> LabelSnapshotSealing.seal(copy, envelopeCrypto, keys, out) }
            ?: return null
        return upload(userId, LabelDriveNaming.fileName(ownDeviceId), sealed, LabelDriveNaming.MEDIA_TYPE, DriveDataFolder.LABELS)
            ?.also { store.recordSaved(SharedBackupKind.LABELS, same) }
    }

    /** Writes this device's hidden list to Drive unless the copy there already holds it. Null when the write failed. */
    suspend fun saveHidden(userId: UserId, ownDeviceId: String, nowMs: Long): Boolean? {
        val copy = HiddenCopyMerge.forDrive(hidden.load(), ownDeviceId, driveStore.deviceLabel, nowMs)
        if (copy.photos.isEmpty() && copy.albums.isEmpty()) return false
        val same = HiddenSnapshotCodec.encode(copy.copy(createdAtMillis = 0L))
        if (store.unchanged(SharedBackupKind.HIDDEN, same)) return false
        val sealed = seal(userId, nowMs) { keys, out -> HiddenSnapshotSealing.seal(copy, envelopeCrypto, keys, out) }
            ?: return null
        return upload(userId, HiddenDriveNaming.fileName(ownDeviceId), sealed, HiddenDriveNaming.MEDIA_TYPE, DriveDataFolder.HIDDEN)
            ?.also { store.recordSaved(SharedBackupKind.HIDDEN, same) }
    }

    /**
     * The backups on Drive, one entry per install, for the list on the Drive card. A device whose name was never
     * read gets it from its label or hidden-list copy, the small ones, when it has one. Null when the folders could
     * not be listed.
     */
    suspend fun devices(userId: UserId): List<DriveDeviceCopies>? {
        val listing = list(userId) ?: return null
        val known = store.deviceNames()
        val learned = HashMap<String, String>()
        for (entry in copiesByDevice(listing, known)) {
            if (entry.name != null) continue
            val label = listing.labels.firstOrNull { LabelDriveNaming.parse(it.name) == entry.deviceId }
                ?.let { (readLabels(userId, SharedBackupCopy(entry.deviceId, it)) as? CopyRead.Read)?.value?.deviceLabel }
            val name = label ?: listing.hidden.firstOrNull { HiddenDriveNaming.parse(it.name) == entry.deviceId }
                ?.let { (readHidden(userId, SharedBackupCopy(entry.deviceId, it)) as? CopyRead.Read)?.value?.deviceLabel }
            if (!name.isNullOrBlank()) learned[entry.deviceId] = name
        }
        store.rememberDeviceNames(learned)
        return copiesByDevice(listing, known + learned)
    }

    /**
     * Moves the face copies of every install but [ownDeviceId] in [listing] to the Drive trash, after a delete of the
     * face data everywhere made here. False when Drive kept one.
     */
    suspend fun trashOtherFaceCopies(userId: UserId, listing: SharedBackupListing, ownDeviceId: String): Boolean {
        val others = listing.ml.filter { file ->
            MlDriveNaming.parse(file.name)?.let { it.kind == MlDriveFileKind.FACES && it.deviceId != ownDeviceId } == true
        }
        if (others.isEmpty()) return true
        val trashed = driveStore.trash(userId, others.map { it.nodeUid }) is MlDriveResult.Ok
        SyncDiagnostics.log("shared backup: ${others.size} face copy(ies) of other devices to the trash: ${if (trashed) "done" else "refused"}")
        return trashed
    }

    /** Moves an install's copies to the Drive trash. False when Drive kept one. */
    suspend fun trash(userId: UserId, copies: DriveDeviceCopies): Boolean =
        driveStore.trash(userId, copies.nodeUids) is MlDriveResult.Ok

    /** Makes sure the [area] folder exists, creating it (the shared backup's switch is the user's agreement). */
    suspend fun ensureFolder(userId: UserId, area: DriveDataFolder): Boolean =
        when (val result = driveStore.folder(userId, createIfMissing = true, area = area)) {
            is MlDriveResult.Ok -> result.value != null
            else -> false
        }

    private suspend fun readLabels(userId: UserId, copy: SharedBackupCopy): CopyRead<LabelSnapshot> {
        val snapshot = readOpened(userId, copy) { input, refresh ->
            envelopeCrypto.openKeys(userId, refresh).use { keys ->
                when (val opened = LabelSnapshotSealing.open(input, envelopeCrypto, keys)) {
                    is LabelSnapshotOpened.Snapshot -> Opened.Value(opened.value)
                    LabelSnapshotOpened.WrongKey -> Opened.WrongKey
                    else -> Opened.Unreadable
                }
            }
        }
        return snapshot.ownedBy(copy.deviceId) { it.deviceId }
    }

    private suspend fun readHidden(userId: UserId, copy: SharedBackupCopy): CopyRead<HiddenSnapshot> {
        val snapshot = readOpened(userId, copy) { input, refresh ->
            envelopeCrypto.openKeys(userId, refresh).use { keys ->
                when (val opened = HiddenSnapshotSealing.open(input, envelopeCrypto, keys)) {
                    is HiddenSnapshotOpened.Snapshot -> Opened.Value(opened.value)
                    HiddenSnapshotOpened.WrongKey -> Opened.WrongKey
                    else -> Opened.Unreadable
                }
            }
        }
        return snapshot.ownedBy(copy.deviceId) { it.deviceId }
    }

    private sealed interface Opened<out T> {
        data class Value<T>(val value: T) : Opened<T>
        data object WrongKey : Opened<Nothing>
        data object Unreadable : Opened<Nothing>
    }

    /**
     * Downloads [copy] and opens it with [open], once more with the account's addresses refetched when no cached key
     * opened it: passed over when no key of this account opens it, it is damaged or too large, tried again later
     * when it could not be fetched now.
     */
    private suspend fun <T> readOpened(
        userId: UserId,
        copy: SharedBackupCopy,
        open: suspend (FileInputStream, Boolean) -> Opened<T>,
    ): CopyRead<T> {
        if (copy.file.sizeBytes > MAX_COPY_BYTES) {
            SyncDiagnostics.log("shared backup: a copy is too large to read, passed over")
            return CopyRead.PassOver
        }
        val scratch = scratchFile("read-") ?: return CopyRead.Retry
        try {
            val downloaded = driveStore.download(userId, copy.file.activeRevisionUid, scratch)
            if (downloaded !is MlDriveResult.Ok) {
                SyncDiagnostics.log("shared backup: a copy could not be downloaded: ${causeOf(downloaded)}")
                return CopyRead.Retry
            }
            for (refresh in listOf(false, true)) {
                val opened = withContext(Dispatchers.IO) { FileInputStream(scratch).use { open(it, refresh) } }
                when (opened) {
                    is Opened.Value -> return CopyRead.Read(opened.value)
                    Opened.WrongKey -> continue
                    Opened.Unreadable -> {
                        SyncDiagnostics.log("shared backup: a copy could not be read, passed over")
                        return CopyRead.PassOver
                    }
                }
            }
            SyncDiagnostics.log("shared backup: a copy is sealed to a key this account does not hold, passed over")
            return CopyRead.PassOver
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncDiagnostics.log("shared backup: a copy could not be read: ${e.javaClass.simpleName}")
            return CopyRead.Retry
        } finally {
            scratch.delete()
        }
    }

    /** Seals into a scratch file stamped with [madeAtMs], so Drive records the copy's own time. */
    private suspend fun seal(
        userId: UserId,
        madeAtMs: Long,
        write: (eu.akoos.photos.data.mlsync.EnvelopeSealKeys, FileOutputStream) -> Unit,
    ): File? {
        val file = scratchFile("save-") ?: return null
        return try {
            withContext(Dispatchers.IO) {
                envelopeCrypto.sealKeys(userId).use { keys -> FileOutputStream(file).use { write(keys, it) } }
                file.setLastModified(madeAtMs)
            }
            file
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            SyncDiagnostics.log("shared backup: a copy could not be sealed: ${e.javaClass.simpleName}")
            file.delete()
            null
        }
    }

    /** Uploads [file] as [name] in [area], retrying once after a conflict. True when written, null when it failed. */
    private suspend fun upload(userId: UserId, name: String, file: File, mediaType: String, area: DriveDataFolder): Boolean? {
        try {
            if (!ensureFolder(userId, area)) return null
            repeat(MAX_UPLOAD_ATTEMPTS) {
                when (val result = driveStore.upload(userId, name, file, mediaType, area)) {
                    is MlDriveResult.Ok -> return true
                    MlDriveResult.Conflict -> Unit
                    else -> {
                        SyncDiagnostics.log("shared backup: upload to ${area.name} failed: ${causeOf(result)}")
                        return null
                    }
                }
            }
            return null
        } finally {
            file.delete()
        }
    }

    private fun scratchFile(prefix: String): File? = try {
        val dir = File(File(context.cacheDir, MlSyncScratch.DIRECTORY), SCRATCH_DIRECTORY)
        dir.mkdirs()
        if (!dir.isDirectory) throw IOException("no scratch directory")
        File.createTempFile(prefix, ".tmp", dir)
    } catch (e: IOException) {
        SyncDiagnostics.log("shared backup: no scratch file: ${e.javaClass.simpleName}")
        null
    }

    /** What a Drive call that did not go through came to, for the diagnostics: a class name or a result name, never a message. */
    private fun causeOf(result: MlDriveResult<*>): String = (result as? MlDriveResult.Failed)?.cause ?: result.javaClass.simpleName

    private companion object {
        const val TAG = "SharedBackup"
        const val SCRATCH_DIRECTORY = "shared"
        const val MAX_UPLOAD_ATTEMPTS = 2

        /** A label or hidden-list copy larger than this is not read: far beyond any real list. */
        const val MAX_COPY_BYTES = 64L * 1024 * 1024
    }
}

/**
 * What reading one other device's copy came to. A copy no key of this account opens, or one damaged or too large,
 * is passed over: it never becomes readable here, and holding the round on it would stop this device saving its
 * own data for good. That device's next copy is read again. One that could not be fetched now is tried again later.
 */
internal sealed interface CopyRead<out T> {
    data class Read<T>(val value: T) : CopyRead<T>
    data object PassOver : CopyRead<Nothing>
    data object Retry : CopyRead<Nothing>
}

/** A read copy whose snapshot names a device other than the one its file name does is passed over. */
internal fun <T> CopyRead<T>.ownedBy(deviceId: String, deviceOf: (T) -> String): CopyRead<T> =
    if (this is CopyRead.Read && deviceOf(value) != deviceId) CopyRead.PassOver else this

/** Whether a face or search copy that ended in [status] is passed over for good rather than read again next round:
 *  no key of this account opens it, or it is damaged. Pure. */
internal fun mlCopyPassedOver(status: MlSyncPartStatus?): Boolean =
    status == MlSyncPartStatus.WRONG_ACCOUNT || status == MlSyncPartStatus.DAMAGED
