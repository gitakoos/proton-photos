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

package eu.akoos.photos.presentation.common

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.util.ShareFileProvider
import eu.akoos.photos.util.ShareIntentBuilder
import eu.akoos.photos.util.StripResult

/** Progress of a batch EXIF-strip. Lives here (not in a screen's UiState) so every grid that owns a
 *  [GalleryItemSelectionController] shares one definition. */
sealed class MultiStripState {
    data object Idle : MultiStripState()
    data object Working : MultiStripState()
    data class Done(val stripped: Int, val skipped: Int) : MultiStripState()
    data class Failed(val message: String) : MultiStripState()
}

/**
 * The multi-select action set shared by every [GalleryItem]-keyed grid (the timeline and search).
 * Owns the selection plus share / add-to-album / back-up / download / offline / hide / delete / strip,
 * including the Android 11+ system-trash and write-permission handshakes and the Undo offer, so the
 * grids do not each re-implement them. Constructed per ViewModel with that ViewModel's scope via the
 * assisted [Factory]; every coroutine runs on [scope], so it dies with the ViewModel.
 *
 * Album and device-folder grids key their selection differently (cloud linkId / device URI) and carry
 * scoped actions, so they keep their own logic and only reuse the shared selection widgets.
 */
class GalleryItemSelectionController @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val offlineStore: OfflineStorageManager,
    private val hiddenStorage: HiddenStorageManager,
    private val transferCenter: TransferCenter,
    private val undoController: UndoController,
    private val accountManager: AccountManager,
    @ApplicationContext private val context: Context,
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): GalleryItemSelectionController
    }

    // ── Selection ───────────────────────────────────────────────────────────────────────────────
    private val selection = SelectionState<GalleryItem>()
    val selectedItems: StateFlow<Set<GalleryItem>> = selection.flow

    fun toggleSelection(item: GalleryItem) = selection.toggle(item)
    fun setSelection(items: Set<GalleryItem>) = selection.set(items)
    fun clearSelection() = selection.clear()

    /** The drag-select gesture only covers visible cells, so a Select-all passes the whole list in. */
    fun selectAll(items: Collection<GalleryItem>) = selection.set(items.toSet())

    // ── Album picker cache ──────────────────────────────────────────────────────────────────────
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init {
        scope.launch { runCatching { driveRepo.loadAlbumsCached() }.onSuccess { _albums.value = it } }
    }

    // ── One-shot results the screen consumes ────────────────────────────────────────────────────
    private val _shareIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    /** Offline-batch result count (negative = removed), for the screen's snackbar. */
    private val _offlineBatchResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val offlineBatchResult: SharedFlow<Int> = _offlineBatchResult.asSharedFlow()

    private val offlinePinIds: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // ── Share ───────────────────────────────────────────────────────────────────────────────────
    fun shareSelected() {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            val uris = ArrayList<Uri>(items.size)
            for (item in items) {
                runCatching {
                    when (item) {
                        is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                        is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                        is GalleryItem.CloudOnly -> {
                            val uid = userId ?: error("Not signed in")
                            val file = driveRepo.downloadFullResPhoto(uid, item.cloud)
                            FileProvider.getUriForFile(
                                context, "${context.packageName}.share.fileprovider", file,
                            ).also {
                                ShareFileProvider.putDisplayName(it, item.cloud.displayName)
                            }
                        }
                    }
                }.onSuccess { uris.add(it) }
                    .onFailure { Log.w(TAG, "share resolve failed: ${it.message}") }
            }
            if (uris.isNotEmpty()) {
                val mime = ShareIntentBuilder.shareableMime(items)
                _shareIntent.tryEmit(ShareIntentBuilder.buildSendIntent(context, uris, mime))
            }
            selection.clear()
        }
    }

    // ── Add to album ────────────────────────────────────────────────────────────────────────────
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val cloudLinkIds = items.mapNotNull { item ->
                when (item) {
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            val localUris = items.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }
            val joined = if (cloudLinkIds.isNotEmpty()) {
                runCatching { driveRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds) }
                    .getOrNull()?.succeededLinkIds?.size ?: 0
            } else 0
            val queued = if (localUris.isNotEmpty()) {
                forceUploadLocalUris.queueForAlbum(userId, albumLinkId, localUris)
            } else 0
            selection.clear()
            onResult(joined, queued)
        }
    }

    // ── Back up (upload the not-yet-backed-up local items) ──────────────────────────────────────
    fun backUpSelected(onResult: (queued: Int) -> Unit) {
        val localUris = selection.value.filterIsInstance<GalleryItem.LocalOnly>().map { it.local.uri }
        if (localUris.isEmpty()) return
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val queued = forceUploadLocalUris.forceUpload(userId, localUris)
            selection.clear()
            onResult(queued)
        }
    }

    // ── Download (cloud items to Pictures/, cloud-album photos into their album folder) ──────────
    fun downloadSelected(onResult: (succeeded: Int, failed: Int) -> Unit) {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // One thumbnail URI per selected photo so the Activity screen lists them individually.
            val thumbUris = items.map { thumbUriFor(it) }
            val transferId = transferCenter.start(
                TransferCenter.Kind.DOWNLOAD, items.size, items = thumbUris,
                onCancel = { job?.cancel() },
            )
            val memberships: Map<String, String> = runCatching { driveRepo.getAlbumMemberships(userId) }
                .getOrElse { emptyMap() }
                .mapValues { (_, name) -> ProtonPhotosStorage.sanitize(name) }
            val savedUris = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val result = try {
                downloadPhotos.downloadGalleryItems(
                    userId, items,
                    folderName = "",
                    folderByLinkId = memberships,
                    onSaved = { savedUris.add(it) },
                ) { progress -> transferCenter.progress(transferId, progress.done) }
            } finally {
                transferCenter.finish(transferId)
            }
            transferCenter.log(TransferCenter.Kind.DOWNLOAD, result.done - result.failed, uris = savedUris.toList())
            selection.clear()
            onResult(result.done - result.failed, result.failed)
        }
    }

    /** Thumbnail URI for one gallery item: the on-device file for local/synced photos, the cached
     *  cloud thumbnail (which may not exist yet) for cloud-only ones. */
    private fun thumbUriFor(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly ->
            "file://" + java.io.File(context.cacheDir, "thumbnails/thumb_${item.cloud.linkId}.jpg").absolutePath
    }

    // ── Make available offline (toggle for the cloud-only items) ────────────────────────────────
    fun toggleSelectedOffline() {
        val cloudItems = selection.value.filterIsInstance<GalleryItem.CloudOnly>()
        if (cloudItems.isEmpty()) return
        val pinned = offlinePinIds.value
        val allOffline = cloudItems.all { it.cloud.linkId in pinned }

        if (allOffline) {
            val linkIds = cloudItems.map { it.cloud.linkId }
            scope.launch {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - linkIds.toSet()
                }
                linkIds.forEach { offlineStore.delete(it) }
                selection.clear()
                _offlineBatchResult.emit(-linkIds.size)
            }
            return
        }

        val toPin = cloudItems.filter { it.cloud.linkId !in pinned }
        val linkIds = toPin.map { it.cloud.linkId }
        scope.launch {
            // Optimistic pin so the badges light up before any byte lands; failures below revert.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = current + linkIds
            }
            selection.clear()
            val userId = accountManager.getPrimaryUserId().first()
            var succeeded = 0
            val failedLinkIds = mutableListOf<String>()
            val savedPaths = mutableListOf<String>()
            val transferId = transferCenter.start(TransferCenter.Kind.OFFLINE, toPin.size)
            try {
                for (item in toPin) {
                    val linkId = item.cloud.linkId
                    try {
                        val uid = userId ?: error("Not signed in")
                        val file = driveRepo.downloadFullResPhoto(uid, item.cloud)
                        val stored = offlineStore.store(linkId, file)
                        savedPaths += "file://${stored.absolutePath}"
                        succeeded++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "offline pin failed: ${e.message}")
                        failedLinkIds += linkId
                    }
                    transferCenter.progress(transferId, succeeded + failedLinkIds.size)
                }
            } finally {
                transferCenter.finish(transferId)
            }
            if (failedLinkIds.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - failedLinkIds.toSet()
                }
                failedLinkIds.forEach { offlineStore.delete(it) }
            }
            transferCenter.log(TransferCenter.Kind.OFFLINE, succeeded, uris = savedPaths)
            _offlineBatchResult.emit(succeeded)
        }
    }

    // ── Delete / hide + system-trash permission handshake ───────────────────────────────────────
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _pendingDeleteIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingDeleteIntent: StateFlow<PendingIntent?> = _pendingDeleteIntent.asStateFlow()

    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    private var pendingHidePrivateUris: List<String> = emptyList()
    private var pendingHideSourceFolders: List<String> = emptyList()
    private var pendingHideOriginalNames: List<String> = emptyList()
    private var pendingHideCloudIds: List<String> = emptyList()

    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _isDeleting.value = true
            try {
                when (val result = deletePhotoUseCase(userId, items, freeUpSpace, deleteFromCloud)) {
                    is DeletePhotoUseCase.Result.Success -> {
                        // No system-trash dialog was needed, so device copies were untouched or removed
                        // permanently (pre-R): only a cloud trash is reversible, localRecoverable = false.
                        buildDeleteUndoAction(items, freeUpSpace, deleteFromCloud, hide = false, localRecoverable = false)
                            ?.let { undoController.offer(it) }
                        selection.clear()
                    }
                    is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                        pendingPermissionResult = result
                        _pendingDeleteIntent.value = result.pendingIntent
                    }
                    is DeletePhotoUseCase.Result.CloudDeleteFailed -> Unit
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun hideSelected() {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        // Only device-only photos move into the vault. A synced (green) photo keeps its device file
        // in place and hides client-side by its cloud linkId, exactly like a cloud-only photo, so the
        // shared merge filter drops it everywhere and unhide re-includes it with no re-pairing.
        val vaultable = items.filterIsInstance<GalleryItem.LocalOnly>()
        val cloudFilterIds = items.mapNotNull {
            when (it) {
                is GalleryItem.Synced    -> it.cloud.linkId
                is GalleryItem.CloudOnly -> it.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
        }
        scope.launch {
            _isDeleting.value = true
            try {
                // Client-side hide for the synced + cloud-only members: add their linkIds to the
                // hidden set so they drop from every listing. Nothing on Drive changes.
                if (cloudFilterIds.isNotEmpty()) {
                    context.settingsDataStore.edit { prefs ->
                        val existing = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
                        prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = existing + cloudFilterIds
                    }
                }
                if (vaultable.isEmpty()) {
                    // Pure cloud-only selection: the client-side hide above is the whole operation.
                    selection.clear()
                    return@launch
                }
                // Copy every device file into app-private hidden storage. A synced photo also stashes
                // its cloud linkId so unhide can re-pair by id instead of re-uploading.
                val collected = mutableListOf<String>()
                val folderEntries = mutableListOf<String>()
                val nameEntries = mutableListOf<String>()
                val cloudIdEntries = mutableListOf<String>()
                for (item in vaultable) {
                    val local = when (item) {
                        is GalleryItem.LocalOnly -> item.local
                        is GalleryItem.Synced    -> item.local
                        else                     -> continue
                    }
                    val cloudLinkId = (item as? GalleryItem.Synced)?.cloud?.linkId
                    val sourceFolder = hiddenStorage.sourceFolderFor(local.uri, local.bucketName)
                    val privateUri = hiddenStorage.store(
                        local.uri, local.displayName, local.mimeType, captureTimeMs = local.dateTaken,
                    )
                    if (privateUri != null) {
                        collected += privateUri
                        if (!sourceFolder.isNullOrBlank()) folderEntries += "$privateUri|$sourceFolder"
                        if (local.displayName.isNotBlank()) nameEntries += "$privateUri|${local.displayName}"
                        if (cloudLinkId != null) cloudIdEntries += "$privateUri|$cloudLinkId"
                    }
                }
                if (collected.isEmpty()) return@launch
                pendingHidePrivateUris = collected
                pendingHideSourceFolders = folderEntries
                pendingHideOriginalNames = nameEntries
                pendingHideCloudIds = cloudIdEntries

                val userId = accountManager.getPrimaryUserId().first() ?: run {
                    rollbackPendingHide()
                    return@launch
                }
                when (val result = deletePhotoUseCase(userId, vaultable, freeUpSpace = true, deleteFromCloud = false, hide = true)) {
                    is DeletePhotoUseCase.Result.Success -> {
                        // Snapshot before commitPendingHide() clears the pending list, so Undo restores
                        // exactly the vault URIs that were just committed.
                        val hideUris = pendingHidePrivateUris
                        commitPendingHide()
                        buildHideUndoAction(hideUris)?.let { undoController.offer(it) }
                        selection.clear()
                    }
                    is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                        pendingPermissionResult = result
                        _pendingDeleteIntent.value = result.pendingIntent
                    }
                    is DeletePhotoUseCase.Result.CloudDeleteFailed -> rollbackPendingHide()
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private suspend fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        val folderEntries = pendingHideSourceFolders
        val nameEntries = pendingHideOriginalNames
        val cloudIdEntries = pendingHideCloudIds
        pendingHidePrivateUris = emptyList()
        pendingHideSourceFolders = emptyList()
        pendingHideOriginalNames = emptyList()
        pendingHideCloudIds = emptyList()
        if (uris.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uris
            if (folderEntries.isNotEmpty()) {
                val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = folders + folderEntries
            }
            if (nameEntries.isNotEmpty()) {
                val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = names + nameEntries
            }
            if (cloudIdEntries.isNotEmpty()) {
                val cloudIds = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = cloudIds + cloudIdEntries
            }
        }
    }

    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        pendingHideSourceFolders = emptyList()
        pendingHideOriginalNames = emptyList()
        pendingHideCloudIds = emptyList()
        for (u in uris) hiddenStorage.delete(u)
    }

    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        scope.launch {
            if (pending != null) {
                val userId = accountManager.getPrimaryUserId().first()
                if (userId != null) {
                    deletePhotoUseCase.completeAfterPermissionGranted(
                        userId = userId,
                        cloudLinkIds = pending.cloudLinkIds,
                        items = pending.itemsBeingDeleted,
                        freeUpSpace = pending.freeUpSpace,
                        hide = pending.hide,
                    )
                    // Snapshot the hide URIs before commit clears them, then offer Undo for whichever
                    // reversible action landed.
                    val hideUris = pendingHidePrivateUris
                    if (pending.hide) commitPendingHide()
                    val undo: UndoAction? = when {
                        pending.hide && hideUris.isNotEmpty() -> buildHideUndoAction(hideUris)
                        !pending.hide ->
                            // The system trash keeps the local files for ~30 days, so a confirmed delete
                            // (cloud and/or device) is reversible: localRecoverable = true.
                            buildDeleteUndoAction(
                                pending.itemsBeingDeleted,
                                pending.freeUpSpace,
                                deleteFromCloud = pending.cloudLinkIds.isNotEmpty(),
                                hide = false,
                                localRecoverable = true,
                            )
                        else -> null
                    }
                    if (undo != null) undoController.offer(undo)
                }
            }
            selection.clear()
        }
    }

    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        rollbackPendingHide()
    }

    // ── Batch EXIF strip (+ Android 11+ write-permission handshake) ─────────────────────────────
    private val _multiStripState = MutableStateFlow<MultiStripState>(MultiStripState.Idle)
    val multiStripState: StateFlow<MultiStripState> = _multiStripState.asStateFlow()

    private val _pendingStripIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingStripIntent: StateFlow<PendingIntent?> = _pendingStripIntent.asStateFlow()

    private var pendingStripConfig: MetadataStripConfig? = null
    private var pendingStripUris: List<String> = emptyList()
    private var pendingStripStripped = 0
    private var pendingStripSkipped = 0

    fun stripMetadataSelected() {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            _multiStripState.value = MultiStripState.Working
            val prefs = context.settingsDataStore.data.first()
            val config = MetadataStripConfig(
                stripGps          = prefs[SettingsKeys.STRIP_GPS] == true,
                stripCameraInfo   = prefs[SettingsKeys.STRIP_CAMERA_INFO] == true,
                stripTimestamp    = prefs[SettingsKeys.STRIP_TIMESTAMP] == true,
                stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] == true,
            )
            if (config.isNoOp) {
                _multiStripState.value = MultiStripState.Failed(
                    context.getString(R.string.gallery_enable_metadata_category),
                )
                return@launch
            }
            // Cloud-only items get bucketed into "skipped" (no local bytes to mutate here).
            val localUris = mutableListOf<String>()
            var skippedCloud = 0
            for (item in items) {
                when (item) {
                    is GalleryItem.LocalOnly -> localUris += item.local.uri
                    is GalleryItem.Synced    -> localUris += item.local.uri
                    is GalleryItem.CloudOnly -> skippedCloud++
                }
            }
            runStripPass(config, localUris, baseStripped = 0, baseSkipped = skippedCloud)
        }
    }

    private suspend fun runStripPass(
        config: MetadataStripConfig,
        uris: List<String>,
        baseStripped: Int,
        baseSkipped: Int,
    ) {
        val needsPermission = mutableListOf<String>()
        val (stripped, failed) = withContext(Dispatchers.IO) {
            var ok = 0
            var failedCount = 0
            for (uri in uris) {
                when (ExifHelper.stripFieldsInPlace(context, uri, config)) {
                    is StripResult.Stripped        -> ok++
                    is StripResult.NeedsPermission  -> needsPermission += uri
                    is StripResult.Failed          -> failedCount++
                }
            }
            ok to failedCount
        }
        val totalStripped = baseStripped + stripped
        val totalSkipped  = baseSkipped + failed

        if (needsPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingStripConfig   = config
            pendingStripUris     = needsPermission
            pendingStripStripped = totalStripped
            pendingStripSkipped  = totalSkipped
            _pendingStripIntent.value = MediaStore.createWriteRequest(
                context.contentResolver, needsPermission.map(Uri::parse),
            )
            _multiStripState.value = MultiStripState.Idle
            return
        }
        selection.clear()
        _multiStripState.value = MultiStripState.Done(totalStripped, totalSkipped + needsPermission.size)
    }

    /** Re-runs the strip on the foreign URIs after the user granted the write-permission dialog. */
    fun onStripPermissionGranted() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        clearPendingStripState()
        _pendingStripIntent.value = null
        scope.launch {
            _multiStripState.value = MultiStripState.Working
            runStripPass(config, uris, baseStripped, baseSkipped)
        }
    }

    /** User canceled the write-permission dialog — count the deferred URIs as skipped, no retry. */
    fun clearPendingStripIntent() {
        val baseStripped = pendingStripStripped
        val deferred = pendingStripUris.size + pendingStripSkipped
        clearPendingStripState()
        _pendingStripIntent.value = null
        selection.clear()
        _multiStripState.value = MultiStripState.Done(baseStripped, deferred)
    }

    private fun clearPendingStripState() {
        pendingStripConfig = null
        pendingStripUris = emptyList()
        pendingStripStripped = 0
        pendingStripSkipped = 0
    }

    fun resetMultiStripState() {
        _multiStripState.value = MultiStripState.Idle
    }

    private companion object {
        const val TAG = "SelectionCtl"
    }
}
