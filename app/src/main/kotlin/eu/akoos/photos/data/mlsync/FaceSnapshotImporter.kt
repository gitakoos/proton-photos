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

package eu.akoos.photos.data.mlsync

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceReattachData
import eu.akoos.photos.data.face.FaceReattachSnapshot
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import eu.akoos.photos.domain.usecase.isPortableFaceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

private const val TAG = "FaceSnapshotImport"

private const val READ_BUFFER_BYTES = 64 * 1024

/** How far back the input stays rewindable: past an envelope's clear header and key packet, which is all
 *  an open reads before it can answer that no key fits. */
private const val ENVELOPE_HEAD_BYTES = 128 * 1024

/** Faces read per page while looking for one on a cloud photo, with no embedding read. */
private const val CLOUD_FACE_PAGE = 2000

/** The outcome of a face snapshot import. Only [Imported] and [NamesStaged] changed anything. */
sealed interface FaceSnapshotImportResult {

    /**
     * A snapshot of this device's face pipeline was imported: [faces] rows written, [facesUpdated] faces
     * already here confirmed or removed by a Merge, [people] names carried, [scanMarkers] scanned photos
     * taken. The imported faces wait ungrouped, so the caller groups them. [regroupRequested]: the
     * snapshot grouped its people under other clustering parameters, so the next indexing pass that finds
     * the library complete regroups it here even without that call.
     */
    data class Imported(
        val faces: Int,
        val facesUpdated: Int,
        val people: Int,
        val scanMarkers: Int,
        val regroupRequested: Boolean,
    ) : FaceSnapshotImportResult

    /**
     * A snapshot of another face model: its vectors were left out, its names ([people] of them) staged to
     * be put back on this device's own faces by where each sat once the library is detected, and
     * [facesUpdated] faces already here took theirs at once in a Merge.
     */
    data class NamesStaged(val people: Int, val facesUpdated: Int) : FaceSnapshotImportResult

    /** No key of this account opens the envelope: another account's snapshot. */
    data object WrongAccount : FaceSnapshotImportResult

    /** A snapshot or envelope version this build does not read, written by a newer one. */
    data object UnsupportedVersion : FaceSnapshotImportResult

    /** Not a sealed face snapshot, one cut short, altered or malformed, or not the file it was picked as. */
    data object Unreadable : FaceSnapshotImportResult

    /**
     * Storage is too full for the import: the free space it needs was not there before it began, or ran
     * out while it wrote and the transaction rolled back. Nothing changed.
     */
    data object NotEnoughSpace : FaceSnapshotImportResult
}

/**
 * Restores a sealed FACES snapshot ([FaceSnapshotExporter]'s output) into an account's face data, as a
 * [FaceSnapshotMode.Replace] or a [FaceSnapshotMode.Merge].
 *
 * The envelope is opened into an app-private scratch file (only a fully verified plaintext is read), the
 * plaintext is read once to the end to refuse a malformed one before anything is written, and then
 * streamed into the database by [FaceSnapshotApplier] inside a single transaction, so the import becomes
 * visible whole once the reader has reached the end marker, and any failure leaves the database as it
 * was. The scratch file is deleted either way. A full import first checks the free space it needs on the
 * database's volume ([FACE_IMPORT_SPACE_FACTOR]), and storage running full in the transaction reads as
 * [FaceSnapshotImportResult.NotEnoughSpace].
 *
 * A snapshot of this device's face pipeline and model imports faces, names, curation and scan markers
 * (a face-free photo's too, so it is not scanned here again); one of another pipeline or model imports
 * only its names and curation, through the reattach path a model change uses. Clustering is left to the
 * caller, as after the v3 import; a snapshot grouped under other clustering parameters also asks the
 * indexer for a regroup.
 *
 * The app builds it from [AppDatabase]; a test hands in its own reading of the free space.
 */
class FaceSnapshotImporter internal constructor(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val sealing: FaceSnapshotSealing,
    private val freeBytes: () -> Long?,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase,
        sealing: FaceSnapshotSealing,
    ) : this(context, appDatabase, sealing, { databaseFreeBytes(context, appDatabase) })

    /**
     * Whether a restore into [userId]'s face data has to ask Merge or Replace ([restoreNeedsChoice]): it
     * does unless this device holds no face of a cloud photo and no names wait to be put back. A null
     * [userId] reads the guest partition.
     */
    suspend fun needsModeChoice(userId: UserId?): Boolean = withContext(Dispatchers.IO) {
        restoreNeedsChoice(
            holdsCloudFaces = holdsCloudFaces(userId?.id ?: PhotoLocationEntity.LOCAL_USER),
            reattachPending = reattachFile.isFile,
        )
    }

    /** Whether [account] holds a face of a cloud photo, the rows a Replace clears. Stops at the first one. */
    private suspend fun holdsCloudFaces(account: String): Boolean {
        var after = ""
        while (true) {
            val page = appDatabase.faceDao().faceHeadsAfter(account, after, CLOUD_FACE_PAGE)
            if (page.any { isPortableFaceKey(it.photoKey) }) return true
            if (page.size < CLOUD_FACE_PAGE) return false
            after = page.last().id
        }
    }

    /**
     * Deletes every face row [userId] holds here, the faces, scan markers, people and their curation, and
     * the names waiting to be put back: what a sign-out wipes, for an import that committed after that wipe.
     */
    suspend fun forgetAccount(userId: UserId) = withContext(Dispatchers.IO) {
        val account = userId.id
        appDatabase.withTransaction {
            appDatabase.faceDao().clearForUser(account)
            appDatabase.faceScanDao().clearForUser(account)
            appDatabase.personDao().clearForUser(account)
            appDatabase.personManualPhotoDao().clearForUser(account)
            appDatabase.notPersonDao().clearForUser(account)
            appDatabase.personCoverDao().clearForUser(account)
            appDatabase.clusterSummaryDao().clearForUser(account)
        }
        FaceReattachSnapshot.clear(reattachFile)
    }

    /**
     * Opens the sealed snapshot in [input] (read to its end, left open) and imports it into [userId]'s face
     * data under [mode]. Blocking work runs on the IO dispatcher. The caller stands the face indexer down
     * first, as the Settings import does, so a walk already under way cannot detect a photo the snapshot
     * brings all over again and write over its faces and names. [expectedDeviceId] is the install the file
     * was picked as, from its Drive name; a snapshot another install wrote is refused before anything is
     * written ([snapshotFromExpectedDevice]). Null for a file picked from storage. [releaseInput] runs once
     * the envelope has opened and verified, before anything is written: [input] is read to its end by then,
     * so a caller can close it and delete the file behind it, leaving that space to the import.
     */
    suspend fun import(
        userId: UserId,
        input: InputStream,
        mode: FaceSnapshotMode,
        expectedDeviceId: String? = null,
        releaseInput: () -> Unit = {},
    ): FaceSnapshotImportResult =
        withContext(Dispatchers.IO) {
            val plaintext = FaceSnapshotScratch.newFile(context)
            try {
                when (val opened = open(userId, input, plaintext)) {
                    is EnvelopeOpenResult.Opened -> {
                        releaseInput()
                        importPlaintext(userId, plaintext, mode, expectedDeviceId)
                    }
                    EnvelopeOpenResult.WrongKey -> FaceSnapshotImportResult.WrongAccount
                    is EnvelopeOpenResult.UnsupportedVersion -> FaceSnapshotImportResult.UnsupportedVersion
                    EnvelopeOpenResult.NotAnEnvelope, EnvelopeOpenResult.Corrupt -> {
                        if (opened == EnvelopeOpenResult.Corrupt) Log.w(TAG, "snapshot envelope did not verify")
                        FaceSnapshotImportResult.Unreadable
                    }
                }
            } catch (e: IOException) {
                if (isStorageFull(e)) {
                    Log.w(TAG, "snapshot not opened: storage full")
                    FaceSnapshotImportResult.NotEnoughSpace
                } else {
                    Log.w(TAG, "snapshot read failed: ${e.javaClass.simpleName}")
                    FaceSnapshotImportResult.Unreadable
                }
            } finally {
                plaintext.delete()
            }
        }

    /**
     * Opens the envelope in [input] into [into] with the account's cached keys, and once more with its
     * addresses refetched when none of those fit, for a key another device made since this one cached
     * them. The input is rewound for that second try; a key that fails to load counts as no key.
     */
    private suspend fun open(userId: UserId, input: InputStream, into: File): EnvelopeOpenResult {
        val source = BufferedInputStream(input, READ_BUFFER_BYTES)
        source.mark(ENVELOPE_HEAD_BYTES)
        val first = openKeysOrNull(userId, refresh = false)?.use { keys -> openInto(source, into, keys) }
            ?: EnvelopeOpenResult.WrongKey
        if (first != EnvelopeOpenResult.WrongKey) return first
        try {
            source.reset()
        } catch (e: IOException) {
            return first
        }
        val refreshed = openKeysOrNull(userId, refresh = true) ?: return first
        return refreshed.use { keys -> openInto(source, into, keys) }
    }

    private fun openInto(source: InputStream, into: File, keys: EnvelopeOpenKeys): EnvelopeOpenResult =
        BufferedOutputStream(FileOutputStream(into)).use { out ->
            MlSnapshotEnvelope.open(source, out, sealing.crypto, keys)
        }

    private suspend fun openKeysOrNull(userId: UserId, refresh: Boolean): EnvelopeOpenKeys? =
        try {
            sealing.openKeys(userId, refresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "snapshot keys unavailable: ${e.javaClass.simpleName}")
            null
        }

    /**
     * Imports the verified plaintext snapshot in [plaintext] into [userId]'s face data under [mode], unless
     * it names another install than [expectedDeviceId] or a full import would not fit in the free space. A
     * null [userId] writes the guest partition.
     */
    internal suspend fun importPlaintext(
        userId: UserId?,
        plaintext: File,
        mode: FaceSnapshotMode,
        expectedDeviceId: String? = null,
    ): FaceSnapshotImportResult {
        val survey = try {
            FileInputStream(plaintext).use { surveyFaceSnapshot(it) } ?: return FaceSnapshotImportResult.Unreadable
        } catch (e: UnsupportedMlSnapshotVersionException) {
            return FaceSnapshotImportResult.UnsupportedVersion
        } catch (e: IOException) {
            Log.w(TAG, "snapshot refused: ${e.javaClass.simpleName}")
            return FaceSnapshotImportResult.Unreadable
        }
        if (!snapshotFromExpectedDevice(survey.header.deviceId, expectedDeviceId)) {
            Log.w(TAG, "snapshot refused: another install wrote it")
            return FaceSnapshotImportResult.Unreadable
        }
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val fit = faceSnapshotFit(survey.header)
        // A names-only import writes no vectors, so only a full one is measured against the free space.
        if (fit == FaceSnapshotFit.Full && !importFits(plaintext.length(), FACE_IMPORT_SPACE_FACTOR, freeBytes())) {
            Log.w(TAG, "snapshot refused: not enough free space")
            return FaceSnapshotImportResult.NotEnoughSpace
        }
        val applied = try {
            appDatabase.withStorageAwareTransaction {
                val applier = FaceSnapshotApplier(appDatabase, account, mode, fit, survey)
                val result = FileInputStream(plaintext).use { applier.run(MlSnapshotReader(it)) }
                // Inside the transaction, so a failure to stage the names rolls the rows back with it.
                if (fit == FaceSnapshotFit.NamesOnly) stageForReattach(result.staged, mode)
                result
            }
        } catch (e: Exception) {
            if (isStorageFull(e)) {
                Log.w(TAG, "snapshot import rolled back: storage full")
                return FaceSnapshotImportResult.NotEnoughSpace
            }
            if (e !is IOException) throw e
            Log.w(TAG, "snapshot import rolled back: ${e.javaClass.simpleName}")
            return FaceSnapshotImportResult.Unreadable
        }
        markModelVersionIfUnset()
        return when (fit) {
            FaceSnapshotFit.Full -> {
                if (mode == FaceSnapshotMode.Replace) dropPendingCloudLabels()
                val regroup = snapshotNeedsRegroup(survey.header)
                if (regroup) requestRegroup()
                FaceSnapshotImportResult.Imported(
                    faces = applied.facesWritten,
                    facesUpdated = applied.facesUpdated,
                    people = applied.people,
                    scanMarkers = applied.scanMarkers,
                    regroupRequested = regroup,
                )
            }
            FaceSnapshotFit.NamesOnly -> FaceSnapshotImportResult.NamesStaged(applied.people, applied.facesUpdated)
        }
    }

    private val reattachFile: File
        get() = FaceReattachSnapshot.file(File(context.filesDir, FaceModelAssets.DIRECTORY))

    /**
     * Adds [staged] to the names waiting for the reattach path, never over them: a snapshot still on disk
     * holds curation an earlier wipe or import has not put back yet. The two are merged
     * ([FaceReattachSnapshot.merge]): on Replace the waiting names of cloud photos give way to the
     * snapshot's, which win where both speak for one face; on Merge the waiting ones win, as this device's
     * own curation does wherever a Merge lands ([mergeFaceCuration]).
     */
    private fun stageForReattach(staged: FaceReattachData, mode: FaceSnapshotMode) {
        if (staged.isEmpty()) return
        FaceReattachSnapshot.update(reattachFile) { waiting ->
            when {
                waiting == null -> staged
                mode == FaceSnapshotMode.Replace ->
                    FaceReattachSnapshot.merge(under = withoutCloudPhotos(waiting), over = staged)
                else -> FaceReattachSnapshot.merge(under = staged, over = waiting)
            }
        }
    }

    /**
     * After a Replace, the names still waiting for the reattach path on cloud photos would land on the
     * snapshot's faces at the next drained indexing pass and undo it, so they are let go; the device-only
     * photos' stay. Best effort: failing here leaves those names to win on their faces, and loses nothing.
     */
    private fun dropPendingCloudLabels() {
        runCatching {
            FaceReattachSnapshot.update(reattachFile) { waiting -> waiting?.let { withoutCloudPhotos(it) } }
        }.onFailure { Log.w(TAG, "pending reattach trim failed: ${it.javaClass.simpleName}") }
    }

    private fun withoutCloudPhotos(data: FaceReattachData) = FaceReattachData(
        members = data.members.filterNot { isPortableFaceKey(it.photoKey) },
        manual = data.manual.filterNot { isPortableFaceKey(it.photoKey) },
        nots = data.nots.filterNot { isPortableFaceKey(it.photoKey) },
        rejected = data.rejected.filterNot { isPortableFaceKey(it.photoKey) },
    )

    /**
     * Records that the face index belongs to this build's pipeline when nothing is recorded yet (a device
     * that never indexed), so the first indexing walk does not take the import for a model change and wipe
     * it, as the v3 import does. A different recorded generation is a migration still to run, which is left
     * to run: it keeps the names by where each face sat and re-detects the stale faces.
     */
    private suspend fun markModelVersionIfUnset() {
        runCatching {
            context.settingsDataStore.edit { prefs ->
                if (prefs[SettingsKeys.FACE_MODEL_VERSION_KEY] == null) {
                    prefs[SettingsKeys.FACE_MODEL_VERSION_KEY] = FACE_MODEL_VERSION
                }
            }
        }.onFailure { rethrowCancellation(it) }
    }

    /** Clears the recorded clustering generation, so the next indexing pass that finds the library complete
     *  regroups the stored faces once under this device's parameters. */
    private suspend fun requestRegroup() {
        runCatching {
            context.settingsDataStore.edit { it.remove(SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY) }
        }.onFailure { rethrowCancellation(it) }
    }

    private fun rethrowCancellation(failure: Throwable) {
        if (failure is CancellationException) throw failure
        Log.w(TAG, "face index settings not saved: ${failure.javaClass.simpleName}")
    }
}
