package me.proton.photos.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.StorageFullException
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.repository.SyncStateRepository
import me.proton.photos.util.ExifHelper
import me.proton.photos.util.MetadataStripConfig
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val UPLOAD_TAG = "UploadUseCase"

/**
 * Per-file upload event surfaced to UI (Settings sync progress).
 *
 * Status transitions per file: `Queued` → `Uploading` → (`Done` | `Failed`). `done` and `total`
 * are batch-level counters so the UI can render a single linear bar without keeping its own state.
 *
 * `Idle` is a synthetic frame the use-case emits when it finishes (or finds nothing to upload)
 * so observers can clear any in-flight view without watching a separate signal.
 */
enum class UploadStatus { Queued, Uploading, Done, Failed, Idle }

data class UploadProgress(
    val uri: String,
    val displayName: String,
    val status: UploadStatus,
    val doneIdx: Int,
    val totalCount: Int,
)

@Singleton
class UploadPendingUseCase @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /**
     * Hot stream of per-file upload events. Buffered so a slow collector never throttles the
     * upload loop. Replay = 1 so a UI that connects mid-batch sees the latest event immediately.
     */
    private val _progress = MutableSharedFlow<UploadProgress>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val progress: SharedFlow<UploadProgress> = _progress.asSharedFlow()

    /**
     * Returned to the worker so it can distinguish "0 to upload" (success) from "tried N,
     * 0 succeeded" (stale-success bug — used to silently mark sync as done even when every
     * item failed). [SyncWorker] downgrades the verdict when [successCount] is 0 but
     * [attempted] is non-zero so WorkManager retries instead of writing LAST_SYNC_MS.
     */
    data class Result(val attempted: Int, val successCount: Int) {
        val allFailed: Boolean get() = attempted > 0 && successCount == 0
    }

    suspend operator fun invoke(userId: UserId): Result = mutex.withLock {
        // Read folder filter — same source of truth as ReconcileSyncStateUseCase.
        // null = first-run (backup nothing); empty set = all disabled; non-empty = selected folders.
        val prefs = context.settingsDataStore.data.first()
        val selectedFolders: Set<String>? = prefs[SettingsKeys.SYNC_FOLDER_NAMES]

        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: true
        val stripConfig = MetadataStripConfig(
            stripGps = prefs[SettingsKeys.STRIP_GPS] ?: true,
            stripCameraInfo = prefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
            stripTimestamp = prefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
            stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false,
        )

        if (selectedFolders == null || selectedFolders.isEmpty()) {
            Log.d(UPLOAD_TAG, "No backup folders configured — skipping upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }

        val pending = syncStateRepo.observeAll(userId).first()
            .filter { it.status == SyncStatus.LOCAL_ONLY }

        if (pending.isEmpty()) {
            Log.d(UPLOAD_TAG, "No pending items to upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }
        Log.d(UPLOAD_TAG, "Starting upload of ${pending.size} pending item(s)")

        // Build name→linkId map from existing Drive albums (source of truth from cloud).
        // MUTABLE: virtual-album-driven cloud creates below need to merge into this so a
        // second upload to the same virtual album doesn't try to recreate the cloud counterpart.
        val existingAlbumsByName: MutableMap<String, String> = try {
            cloudRepo.loadAlbums(userId).associate { it.name.lowercase() to it.linkId }
                .toMutableMap()
        } catch (e: Exception) {
            Log.w(UPLOAD_TAG, "Could not load existing albums for name matching: ${e.message}")
            mutableMapOf()
        }

        // Virtual-album membership: Map<localContentUri, List<albumName>>. A single photo can
        // sit in multiple virtual albums simultaneously (the user added it to "Italy" AND
        // "Favorites" from inside the app). Each upload below routes to ALL of these cloud
        // albums on top of the bucket-name fallback.
        val virtualMembershipByUri: Map<String, List<String>> = run {
            val raw = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
            raw.mapNotNull { entry ->
                val sep = entry.indexOf("||")
                if (sep <= 0 || sep == entry.length - 2) null
                else entry.substring(0, sep) to entry.substring(sep + 2)
            }.groupBy({ it.second }, { it.first })
        }

        // Rebuild the in-memory bucket→albumLinkId cache from DataStore, but validate each entry
        // against the cloud state. Stale entries (from deleted albums) are dropped so we re-create
        // instead of endlessly trying to add photos to a non-existent album.
        val albumCache = mutableMapOf<String, String>().apply {
            (prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()).forEach { entry ->
                val idx = entry.indexOf('=')
                if (idx > 0) {
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    // Only keep if the cloud still has an album with this name AND its linkId matches
                    val cloudLinkId = existingAlbumsByName[bucket.lowercase()]
                    if (cloudLinkId != null) {
                        put(bucket, cloudLinkId)  // use cloud linkId (authoritative)
                    } else {
                        Log.d(UPLOAD_TAG, "albumCache: dropping stale entry '$bucket'=$storedLinkId (album deleted)")
                    }
                }
            }
        }
        // Persist the cleaned cache immediately so stale entries don't survive app restarts
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
        }

        var successCount = 0
        val totalCount = pending.size
        for ((idx, state) in pending.withIndex()) {
            coroutineContext.ensureActive()
            var strippedFile: File? = null
            try {
                val localItem = localRepo.queryByUri(state.localUri)
                if (localItem == null) {
                    Log.w(UPLOAD_TAG, "Local item not found for URI: ${state.localUri}")
                    continue
                }
                // Guard: skip items from folders that are no longer selected.
                // ReconcileSyncStateUseCase normally cleans these up, but this is a safety net
                // in case a stale LOCAL_ONLY entry survives (e.g. after import / app restart).
                if (localItem.bucketName != null && localItem.bucketName !in selectedFolders) {
                    Log.d(UPLOAD_TAG, "Skipping ${localItem.displayName}: folder '${localItem.bucketName}' not selected for backup")
                    continue
                }
                Log.d(UPLOAD_TAG, "Uploading: ${localItem.displayName} (${localItem.mimeType}, ${localItem.sizeBytes} bytes)")
                _progress.tryEmit(
                    UploadProgress(
                        uri = state.localUri,
                        displayName = localItem.displayName,
                        status = UploadStatus.Uploading,
                        doneIdx = idx,
                        totalCount = totalCount,
                    )
                )

                // Strip metadata into a temp file if configured and this is a JPEG/HEIC image
                val uploadUri: String = if (stripOnUpload && localItem.mimeType.startsWith("image/")) {
                    strippedFile = ExifHelper.stripToTempFile(context, state.localUri, stripConfig)
                    if (strippedFile != null) {
                        Log.d(UPLOAD_TAG, "Metadata stripped for ${localItem.displayName}")
                        android.net.Uri.fromFile(strippedFile).toString()
                    } else {
                        state.localUri
                    }
                } else {
                    state.localUri
                }

                val hash = computeSha256(uploadUri)
                val uploadItem = if (strippedFile != null)
                    localItem.copy(sizeBytes = strippedFile.length())
                else
                    localItem
                val cloudId = cloudRepo.uploadFile(userId, uploadItem, hash, uploadUri)

                strippedFile?.delete()
                strippedFile = null

                syncStateRepo.upsert(
                    state.copy(
                        cloudFileId = cloudId,
                        localHash = hash,
                        status = SyncStatus.SYNCED,
                        lastSyncSuccessMs = System.currentTimeMillis(),
                        backedUpAtMs = System.currentTimeMillis(),
                    ),
                    userId,
                )
                successCount++
                Log.d(UPLOAD_TAG, "Upload OK: ${localItem.displayName} → cloudId=$cloudId")
                _progress.tryEmit(
                    UploadProgress(
                        uri = state.localUri,
                        displayName = localItem.displayName,
                        status = UploadStatus.Done,
                        // doneIdx is 1-based now so the bar can render `idx+1 / total`
                        // (e.g. "1 of 5" after the first file completes).
                        doneIdx = idx + 1,
                        totalCount = totalCount,
                    )
                )

                // Auto-sync to one or more Drive albums. A single uploaded photo can land in
                // multiple cloud albums simultaneously:
                //
                //   - Every virtual-membership album that references its URI (the user added it
                //     to "Italy 2025" / "Favorites" from inside the app).
                //   - The bucket-name album (Camera, Screenshots, …) — same as before.
                //
                // Deduplication is by album name so a virtual-membership entry whose name
                // happens to equal the bucket doesn't get added twice.
                val targetAlbumNames = buildSet {
                    addAll(virtualMembershipByUri[state.localUri].orEmpty())
                    localItem.bucketName?.let { add(it) }
                }
                for (albumName in targetAlbumNames) {
                    try {
                        val albumLinkId = albumCache[albumName]
                            ?: existingAlbumsByName[albumName.lowercase()]?.also { linkId ->
                                // Found an existing album — add to cache so we skip the lookup next time
                                albumCache[albumName] = linkId
                                context.settingsDataStore.edit { p ->
                                    p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                                }
                                Log.d(UPLOAD_TAG, "Matched existing album '$albumName' → $linkId")
                            }
                            ?: run {
                                val newAlbum = cloudRepo.createDriveAlbum(userId, albumName)
                                albumCache[albumName] = newAlbum.linkId
                                // Keep the in-memory name→linkId index in sync so later iterations
                                // in this same batch don't try to re-create the album.
                                existingAlbumsByName[albumName.lowercase()] = newAlbum.linkId
                                context.settingsDataStore.edit { p ->
                                    p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                                }
                                Log.d(UPLOAD_TAG, "Created new album '$albumName' → ${newAlbum.linkId}")
                                newAlbum.linkId
                            }
                        runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                            .onSuccess { Log.d(UPLOAD_TAG, "Added $cloudId to album '$albumName' ($albumLinkId)") }
                            .onFailure { e -> Log.w(UPLOAD_TAG, "addPhotosToAlbum failed for '$albumName': ${e.message}") }
                    } catch (e: Exception) {
                        Log.w(UPLOAD_TAG, "Album sync failed for '$albumName': ${e.message}")
                        // Non-fatal: photo is uploaded, album sync is best-effort
                    }
                }
            } catch (e: StorageFullException) {
                Log.w(UPLOAD_TAG, "Storage full — stopping upload")
                _progress.tryEmit(
                    UploadProgress(
                        uri = state.localUri,
                        displayName = state.localUri.substringAfterLast('/'),
                        status = UploadStatus.Failed,
                        doneIdx = idx,
                        totalCount = totalCount,
                    )
                )
                break
            } catch (e: Exception) {
                Log.e(UPLOAD_TAG, "Upload failed for ${state.localUri}: ${e.message}", e)
                _progress.tryEmit(
                    UploadProgress(
                        uri = state.localUri,
                        // localItem may be null at this point; degrade to the URI tail rather
                        // than crash. UI rows truncate ellipsis-end so this still looks ok.
                        displayName = state.localUri.substringAfterLast('/'),
                        status = UploadStatus.Failed,
                        doneIdx = idx,
                        totalCount = totalCount,
                    )
                )
                // continue with next item
            } finally {
                strippedFile?.delete()
            }
        }
        Log.d(UPLOAD_TAG, "Upload complete: $successCount/${pending.size} succeeded")
        // Final "Idle" frame so the UI clears any in-flight panel.
        _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, totalCount, totalCount))
        Result(attempted = pending.size, successCount = successCount)
    }

    private fun computeSha256(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            return ""
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
