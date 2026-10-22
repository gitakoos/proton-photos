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
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.drive.sdk.DownloadAbortedException
import me.proton.drive.sdk.Downloader
import me.proton.drive.sdk.OperationAbortedException
import me.proton.drive.sdk.ProtonDriveClient
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.Uploader
import me.proton.drive.sdk.entity.AvailableNameTarget
import me.proton.drive.sdk.entity.FileDownloaderRequest
import me.proton.drive.sdk.entity.FileNode
import me.proton.drive.sdk.entity.FileRevisionUploaderRequest
import me.proton.drive.sdk.entity.FileUploaderRequest
import me.proton.drive.sdk.entity.FolderNode
import me.proton.drive.sdk.entity.Node
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.RevisionUid
import me.proton.drive.sdk.entity.UploadResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an [MlDriveStore] call. No SDK exception ever reaches the caller. */
sealed interface MlDriveResult<out T> {
    data class Ok<out T>(val value: T) : MlDriveResult<T>

    /** The Drive SDK cannot run on this device (older Android or an unsupported ABI). */
    data object NotCapable : MlDriveResult<Nothing>

    /** The Drive data folder does not exist; only [MlDriveStore.folder] ever creates it. */
    data object FolderMissing : MlDriveResult<Nothing>

    /**
     * The file changed between the lookup and the upload: the revision compare-and-swap lost, another
     * upload of it was open, or the name appeared meanwhile. List again and retry later.
     */
    data object Conflict : MlDriveResult<Nothing>

    /** Anything else. [cause] is a class name or one of the constants below, never a message. */
    data class Failed(val cause: String) : MlDriveResult<Nothing> {
        companion object {
            /** A node of the wrong type (a file where a folder belongs, or the reverse) holds the name. */
            const val NAME_TAKEN = "NameTaken"

            /** The name is not one of this install's own file names. */
            const val FOREIGN_NAME = "ForeignName"

            /** The local file to upload does not exist. */
            const val NO_LOCAL_FILE = "NoLocalFile"

            /** The download arrived but the SDK could not verify it. */
            const val VERIFICATION_ISSUE = "VerificationIssue"
        }
    }
}

/** A Drive data folder. [nodeUid] is a My files node: never store it in sync_state or photo_listing. */
data class MlDriveFolder(val nodeUid: String)

/** A finished upload: the file's node, its new active revision, and whether the file was created. */
data class MlDriveUpload(val nodeUid: String, val revisionUid: String, val createdFile: Boolean)

/**
 * The signed-in user's Drive copy of the app's own data, kept under My files /
 * [MlDriveNaming.APP_FOLDER_NAME] / a [DriveDataFolder] through the Proton Drive SDK: the machine-learning
 * data in "ML data", the labels in "Labels".
 *
 * Each install writes only its own files (named with its [deviceId], see [DriveDataFolder.deviceIdOf]) and
 * replaces them as new revisions of the same node, so a name never changes; every install of the
 * account can list and download all of them. The folders are created only by [folder] with
 * `createIfMissing`, which callers reach after the user agreed; every other call treats a missing
 * folder as "nothing stored".
 *
 * The resolved folder uids are kept in memory only, and nothing here writes a My files node uid to
 * sync_state or photo_listing. Log lines carry class names and SDK error codes, never names, paths
 * or ids.
 */
@Singleton
class MlDriveStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientProvider: SdkDriveClientProvider,
    private val apiProvider: ApiProvider,
) {
    private val folderMutex = Mutex()
    private val uploadMutex = Mutex()
    private val deviceIdMutex = Mutex()
    private val knownFolders = HashMap<Pair<UserId, DriveDataFolder>, KnownFolders>()

    @Volatile
    private var cachedDeviceId: String? = null

    /** This device's model name, which another device shows next to this install's snapshots. */
    val deviceLabel: String
        get() = MlDriveNaming.deviceLabel(Build.MODEL)

    /**
     * This install's id, created the first time it is asked for and kept in
     * [SettingsKeys.ML_SYNC_DEVICE_ID] across sign-outs. Throws the preferences store's IOException
     * when the value can be neither read nor written.
     */
    suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        return deviceIdMutex.withLock {
            cachedDeviceId ?: run {
                val saved = context.settingsDataStore.edit { prefs ->
                    val stored = prefs[SettingsKeys.ML_SYNC_DEVICE_ID]
                    if (stored == null || !MlDriveNaming.isDeviceId(stored)) {
                        prefs[SettingsKeys.ML_SYNC_DEVICE_ID] = UUID.randomUUID().toString()
                    }
                }[SettingsKeys.ML_SYNC_DEVICE_ID]
                checkNotNull(saved).also { cachedDeviceId = it }
            }
        }
    }

    /**
     * The [area] folder, or `Ok(null)` when it does not exist and [createIfMissing] is false. With
     * [createIfMissing], whichever of the two folders is missing is created. `Failed(NAME_TAKEN)` when
     * a file already holds one of the folder names.
     */
    suspend fun folder(
        userId: UserId,
        createIfMissing: Boolean,
        area: DriveDataFolder = DriveDataFolder.ML,
    ): MlDriveResult<MlDriveFolder?> =
        sdkCall("folder", userId) { client ->
            when (val lookup = dataFolder(client, userId, area, createIfMissing)) {
                is Lookup.Found -> MlDriveResult.Ok(MlDriveFolder(lookup.uid.value))
                Lookup.Missing -> MlDriveResult.Ok(null)
                Lookup.NameTaken -> MlDriveResult.Failed(MlDriveResult.Failed.NAME_TAKEN)
            }
        }

    /**
     * Every live file in the [area] folder, whatever its name, or an empty list when the folder does
     * not exist. [MlDriveNaming.selectForImport] picks the snapshots to restore from it.
     */
    suspend fun list(userId: UserId, area: DriveDataFolder = DriveDataFolder.ML): MlDriveResult<List<MlDriveFile>> =
        sdkCall("list", userId) { client ->
            when (val lookup = dataFolder(client, userId, area, create = false)) {
                is Lookup.Found -> MlDriveResult.Ok(filesIn(client, lookup.uid))
                Lookup.Missing, Lookup.NameTaken -> MlDriveResult.Ok(emptyList())
            }
        }

    /**
     * Stores [localFile] as [name] in the [area] folder: a new file the first time, afterwards a new
     * revision of the existing file based on the active revision it has right now, which the server
     * accepts only if nothing replaced that revision meanwhile. [name] must be one of this install's
     * own file names, so one install never overwrites another's file. Uploads run one at a time.
     */
    suspend fun upload(
        userId: UserId,
        name: String,
        localFile: File,
        mediaType: String,
        area: DriveDataFolder = DriveDataFolder.ML,
    ): MlDriveResult<MlDriveUpload> = sdkCall("upload", userId) { client ->
        if (area.deviceIdOf(name) != deviceId()) {
            return@sdkCall MlDriveResult.Failed(MlDriveResult.Failed.FOREIGN_NAME)
        }
        if (!localFile.isFile) return@sdkCall MlDriveResult.Failed(MlDriveResult.Failed.NO_LOCAL_FILE)
        uploadMutex.withLock {
            val folderUid = when (val lookup = dataFolder(client, userId, area, create = false)) {
                is Lookup.Found -> lookup.uid
                Lookup.Missing, Lookup.NameTaken -> return@sdkCall MlDriveResult.FolderMissing
            }
            val size = localFile.length()
            val modified = Instant.ofEpochMilli(
                localFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            val existing = findChild(client, folderUid, name, AvailableNameTarget.FILE)
            val uploader = when (existing) {
                null -> client.uploader(
                    FileUploaderRequest(
                        parentFolderUid = folderUid,
                        name = name,
                        mediaType = mediaType,
                        fileSize = size,
                        lastModificationTime = modified,
                        // The name carries this install's id, so a draft holding it is this install's
                        // own leftover from an interrupted upload.
                        overrideExistingDraftByOtherClient = true,
                        noWaiting = false,
                    ),
                )
                is FileNode -> client.uploader(
                    FileRevisionUploaderRequest(
                        currentActiveRevisionUid = existing.activeRevision.uid,
                        lastModificationTime = modified,
                        size = size,
                        noWaiting = false,
                    ),
                )
                else -> return@sdkCall MlDriveResult.Failed(MlDriveResult.Failed.NAME_TAKEN)
            }
            val result = uploader.use { sendFile(it, localFile) }
            MlDriveResult.Ok(
                MlDriveUpload(
                    nodeUid = result.nodeUid.value,
                    revisionUid = result.revisionUid.value,
                    createdFile = existing == null,
                ),
            )
        }
    }

    /**
     * Downloads revision [revisionUid] (an [MlDriveFile.activeRevisionUid]) into [destFile], which is
     * replaced only after the SDK verified the whole file. A download the SDK could not verify is
     * `Failed(VERIFICATION_ISSUE)` and leaves [destFile] untouched.
     */
    suspend fun download(userId: UserId, revisionUid: String, destFile: File): MlDriveResult<Unit> =
        sdkCall("download", userId) { client ->
            val target = destFile.absoluteFile
            val partial = File(target.parentFile, target.name + PARTIAL_SUFFIX)
            try {
                target.parentFile?.mkdirs()
                val downloader = client.downloader(
                    FileDownloaderRequest(revisionUid = RevisionUid(revisionUid), noWaiting = false),
                )
                val verified = downloader.use { receiveFile(it, partial) }
                if (!verified) {
                    partial.delete()
                    return@sdkCall MlDriveResult.Failed(MlDriveResult.Failed.VERIFICATION_ISSUE)
                }
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                MlDriveResult.Ok(Unit)
            } catch (t: Throwable) {
                partial.delete()
                throw t
            }
        }

    /**
     * Runs [block] with [userId]'s SDK client off the main thread and folds every failure into a
     * result. The call counts as in flight on the client, so a sign-out never closes the client under
     * it, and an account that is signing out is refused. The SDK's exceptions extend Throwable
     * directly, hence the broad catch; a cancellation of the caller still propagates.
     */
    private suspend fun <T> sdkCall(
        operation: String,
        userId: UserId,
        block: suspend (ProtonDriveClient) -> MlDriveResult<T>,
    ): MlDriveResult<T> = withContext(Dispatchers.IO) {
        try {
            clientProvider.withClient(userId) { client -> block(client) } ?: MlDriveResult.NotCapable
        } catch (t: Throwable) {
            ensureActive()
            Log.w(TAG, "$operation failed: ${describe(t)}")
            if (isConflict(t)) MlDriveResult.Conflict else MlDriveResult.Failed(failureName(t))
        }
    }

    /** Resolves the [area] folder, through the in-memory record while it still checks out. */
    private suspend fun dataFolder(
        client: ProtonDriveClient,
        userId: UserId,
        area: DriveDataFolder,
        create: Boolean,
    ): Lookup =
        folderMutex.withLock {
            knownFolders[userId to area]?.let { known ->
                if (stillInPlace(client, known, area)) return@withLock Lookup.Found(NodeUid(known.dataFolderUid))
                knownFolders.remove(userId to area)
            }
            if (!create && !hasMyFiles(userId)) return@withLock Lookup.Missing
            val myFilesUid = client.getMyFilesFolder().uid
            val appFolderUid =
                when (val lookup = childFolder(client, myFilesUid, MlDriveNaming.APP_FOLDER_NAME, create)) {
                    is Lookup.Found -> lookup.uid
                    else -> return@withLock lookup
                }
            val dataFolderUid =
                when (val lookup = childFolder(client, appFolderUid, area.folderName, create)) {
                    is Lookup.Found -> lookup.uid
                    else -> return@withLock lookup
                }
            knownFolders[userId to area] = KnownFolders(myFilesUid.value, appFolderUid.value, dataFolderUid.value)
            Lookup.Found(dataFolderUid)
        }

    /** Whether both remembered folders are still live, named as expected, and where they were. */
    private suspend fun stillInPlace(client: ProtonDriveClient, known: KnownFolders, area: DriveDataFolder): Boolean {
        val appFolder = client.getNode(NodeUid(known.appFolderUid))
        if (!appFolder.isLiveFolder(MlDriveNaming.APP_FOLDER_NAME, known.myFilesUid)) return false
        val dataFolder = client.getNode(NodeUid(known.dataFolderUid))
        return dataFolder.isLiveFolder(area.folderName, known.appFolderUid)
    }

    private fun Node?.isLiveFolder(expectedName: String, expectedParentUid: String): Boolean =
        this is FolderNode &&
            trashTime == null &&
            parentUid?.value == expectedParentUid &&
            name.getOrNull() == expectedName

    /**
     * Whether the account already has the volume My files lives on. The SDK's My files lookup creates
     * that volume when it is missing, which only a caller allowed to create anything may trigger.
     */
    private suspend fun hasMyFiles(userId: UserId): Boolean =
        apiProvider.get<DriveApiService>(userId).invoke { getVolumes() }.valueOrThrow.volumes
            .any { it.type == MAIN_VOLUME_TYPE && it.state != DELETED_VOLUME_STATE }

    /** The folder [name] under [parentUid], created when missing and [create] is set. */
    private suspend fun childFolder(
        client: ProtonDriveClient,
        parentUid: NodeUid,
        name: String,
        create: Boolean,
    ): Lookup {
        when (val existing = findChild(client, parentUid, name, AvailableNameTarget.FOLDER)) {
            is FolderNode -> return Lookup.Found(existing.uid)
            null -> if (!create) return Lookup.Missing
            else -> return Lookup.NameTaken
        }
        return try {
            Lookup.Found(client.createFolder(parentUid, name).uid).also {
                Log.i(TAG, "created a Drive data folder")
            }
        } catch (t: Throwable) {
            if (!isConflict(t)) throw t
            // Another install created it between the lookup and the create.
            when (val raced = findChild(client, parentUid, name, AvailableNameTarget.FOLDER, checkAvailability = false)) {
                is FolderNode -> Lookup.Found(raced.uid)
                null -> throw t
                else -> Lookup.NameTaken
            }
        }
    }

    /**
     * The live child of [parentUid] named exactly [name], or null. A free name answers in one call;
     * only a taken one walks the children, which costs a node fetch per child.
     */
    private suspend fun findChild(
        client: ProtonDriveClient,
        parentUid: NodeUid,
        name: String,
        target: AvailableNameTarget,
        checkAvailability: Boolean = true,
    ): Node? {
        if (checkAvailability && client.getAvailableName(parentUid, name, target) == name) return null
        for (childUid in client.enumerateFolderChildrenNodeUids(parentUid).toList()) {
            val child = client.getNode(childUid) ?: continue
            if (child.trashTime == null && child.name.getOrNull() == name) return child
        }
        return null
    }

    private suspend fun filesIn(client: ProtonDriveClient, folderUid: NodeUid): List<MlDriveFile> =
        client.enumerateFolderChildrenNodeUids(folderUid).toList().mapNotNull { childUid ->
            val file = client.getNode(childUid) as? FileNode ?: return@mapNotNull null
            val name = file.name.getOrNull()
            if (file.trashTime != null || name == null) return@mapNotNull null
            val revision = file.activeRevision
            MlDriveFile(
                nodeUid = file.uid.value,
                name = name,
                sizeBytes = revision.claimedSize ?: revision.storageSize,
                activeRevisionUid = revision.uid.value,
                serverModifiedAtMs = revision.creationTime.toEpochMilli(),
                serverCreatedAtMs = file.creationTime.toEpochMilli(),
                claimedModifiedAtMs = revision.claimedModificationTime?.toEpochMilli(),
            )
        }

    /** Streams [localFile] through [uploader]; a failed upload is cancelled and its draft disposed. */
    private suspend fun sendFile(uploader: Uploader, localFile: File): UploadResult = coroutineScope {
        FileInputStream(localFile).use { input ->
            val controller = uploader.uploadFromStream(coroutineScope = this, channel = input.channel)
            try {
                controller.tryResume(this)
                controller.awaitCompletion()
            } catch (t: Throwable) {
                quietly {
                    controller.cancel()
                    controller.dispose()
                }
                throw t
            } finally {
                controller.close()
            }
        }
    }

    /** Streams the download into [into]. False when the bytes arrived but could not be verified. */
    private suspend fun receiveFile(downloader: Downloader, into: File): Boolean = coroutineScope {
        FileOutputStream(into).use { output ->
            val controller = downloader.downloadToStream(coroutineScope = this, channel = output.channel)
            try {
                controller.tryResume(this)
                controller.awaitCompletion()
                true
            } catch (aborted: DownloadAbortedException) {
                val unverified = runCatching { controller.isDownloadCompleteWithVerificationIssue() }
                    .getOrDefault(false)
                if (!unverified) throw aborted
                false
            } catch (t: Throwable) {
                quietly { controller.cancel() }
                throw t
            } finally {
                controller.close()
            }
        }
    }

    /** Best-effort cleanup after a failed transfer: never throws and gives up after a few seconds. */
    private suspend fun quietly(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            runCatching { withTimeout(CLEANUP_TIMEOUT_MS) { block() } }
                .onFailure { Log.d(TAG, "transfer cleanup incomplete: ${it.javaClass.simpleName}") }
        }
    }

    /** Every SDK error record carried by [t] and its causes, outermost first. */
    private fun sdkErrors(t: Throwable): Sequence<ProtonSdkError> = sequence {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            var error = (current as? ProtonDriveSdkException)?.error
            while (error != null) {
                yield(error)
                error = error.innerError
            }
            current = current.cause
        }
    }

    /**
     * A lost compare-and-swap or a name clash. The SDK reports a stale or already-open revision as
     * RevisionDraftConflictException and a taken name as NodeWithSameNameExistsException, both over
     * the API's "already exists" code and an HTTP 409.
     */
    private fun isConflict(t: Throwable): Boolean = sdkErrors(t).any { error ->
        error.type in CONFLICT_ERROR_TYPES ||
            error.additionalData is ProtonSdkError.Data.NodeNameConflict ||
            (
                error.domain == ProtonSdkError.ErrorDomain.Api &&
                    (error.primaryCode == ALREADY_EXISTS_CODE || error.secondaryCode == HTTP_CONFLICT)
                )
    }

    /** The most specific class name behind [t]: the SDK's own error type when it carries one. */
    private fun failureName(t: Throwable): String {
        sdkErrors(t).firstOrNull()?.type?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(MAX_NAME_LENGTH) }
        val root = (t as? OperationAbortedException)?.cause ?: t
        return root.javaClass.simpleName.ifEmpty { root.javaClass.name.substringAfterLast('.') }
    }

    private fun describe(t: Throwable): String {
        val error = sdkErrors(t).firstOrNull() ?: return failureName(t)
        return "${failureName(t)} (${error.domain}, ${error.primaryCode}/${error.secondaryCode})"
    }

    private sealed interface Lookup {
        data class Found(val uid: NodeUid) : Lookup
        data object Missing : Lookup
        data object NameTaken : Lookup
    }

    private data class KnownFolders(
        val myFilesUid: String,
        val appFolderUid: String,
        val dataFolderUid: String,
    )

    private companion object {
        const val TAG = "MlDriveStore"
        const val PARTIAL_SUFFIX = ".part"
        const val CLEANUP_TIMEOUT_MS = 5_000L
        const val MAX_CAUSE_DEPTH = 8
        const val MAX_NAME_LENGTH = 80

        /** `drive/volumes` type of the volume My files lives on, and the state of a deleted volume. */
        const val MAIN_VOLUME_TYPE = 1
        const val DELETED_VOLUME_STATE = 2

        /** The API's "already exists" code and the HTTP status a stale revision is refused with. */
        const val ALREADY_EXISTS_CODE = 2500L
        const val HTTP_CONFLICT = 409L

        val CONFLICT_ERROR_TYPES = setOf(
            "RevisionDraftConflictException",
            "NodeWithSameNameExistsException",
        )
    }
}
