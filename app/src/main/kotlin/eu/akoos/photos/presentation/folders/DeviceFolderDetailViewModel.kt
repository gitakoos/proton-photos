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

package eu.akoos.photos.presentation.folders

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.presentation.common.MultiStripState
import android.provider.MediaStore
import android.os.Build
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.common.SelectionState
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.presentation.common.buildDeleteUndoAction
import eu.akoos.photos.presentation.common.buildHideUndoAction
import eu.akoos.photos.presentation.viewer.PublicLinkState
import eu.akoos.photos.R
import javax.inject.Inject

/**
 * Backs [DeviceFolderDetailScreen]: device-resident photos of one MediaStore bucket (LocalOnly + Synced
 * only — CloudOnly has no device file), with uri-keyed selection and upload-to-Drive actions.
 */
@HiltViewModel
class DeviceFolderDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val hiddenStorage: eu.akoos.photos.data.hidden.HiddenStorageManager,
    private val driveRepo: DrivePhotoRepository,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val upload: eu.akoos.photos.domain.usecase.UploadPendingUseCase,
    private val undoController: UndoController,
) : ViewModel() {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    /** Cloud albums the selection can be added to. Seeded once from the local DB cache so the
     *  "Add to album" picker has albums to show without a network round-trip. */
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /** URIs the user has selected. Selection mode is active whenever this is non-empty. */
    private val selection = SelectionState<String>()
    val selectedUris: StateFlow<Set<String>> = selection.flow

    /** Cloud linkIds pinned for offline; drives the per-cell offline badge (a Synced folder item
     *  whose cloud twin is pinned). Backed by the same OFFLINE_PIN_IDS pref the timeline reads. */
    val offlinePinIds: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var primaryUserId: UserId? = null
    private var bucketName: String = ""

    /** One-shot system-share intents emitted to the screen, which launches the chooser. */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    /** Live progress of an in-flight folder back-up. */
    data class BackupProgress(val done: Int, val total: Int)

    /** URIs queued by the most recent back-up action. Drives [backupProgress]; cleared when every
     *  queued photo has finished uploading (or when the screen leaves). */
    private val _backupTarget = MutableStateFlow<Set<String>>(emptySet())

    /** done/total for the active back-up (null when idle). A queued photo counts done once its row flips to Synced. */
    val backupProgress: StateFlow<BackupProgress?> = combine(_items, _backupTarget) { items, target ->
        if (target.isEmpty()) return@combine null
        val done = items.count { it is GalleryItem.Synced && localUriOf(it) in target }
        BackupProgress(done = done, total = target.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { accountManager.getPrimaryUserId().collect { primaryUserId = it } }
        // Drop the progress bar once every queued photo has uploaded.
        viewModelScope.launch {
            backupProgress.collect { p ->
                if (p != null && p.total > 0 && p.done >= p.total) _backupTarget.value = emptySet()
            }
        }
    }

    /** Stop an in-flight folder back-up and clear this screen's progress, while leaving scheduled
     *  auto-backup armed. Cooperative: the photo in transit finishes and backs up, remaining queued
     *  items are not started and stay pending for a later trigger. Never cancels the worker, so the
     *  in-flight native crypto is never interrupted. */
    fun cancelBackup() {
        upload.requestStop()
        _backupTarget.value = emptySet()
    }

    fun load(bucketName: String) {
        this.bucketName = bucketName
        loadAlbums()
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val hiddenUrisFlow = context.settingsDataStore.data.map {
                it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            }
            combine(getGalleryItems.invoke(userId), hiddenUrisFlow) { all, hiddenUris ->
                all.mapNotNull { item ->
                    val (uri, bucket) = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri to item.local.bucketName
                        is GalleryItem.Synced -> item.local.uri to item.local.bucketName
                        is GalleryItem.CloudOnly -> return@mapNotNull null
                    }
                    if (bucket != bucketName || uri in hiddenUris) return@mapNotNull null
                    item
                }.sortedByDescending {
                    when (it) {
                        is GalleryItem.LocalOnly -> it.local.dateTaken
                        is GalleryItem.Synced -> it.local.dateTaken
                        is GalleryItem.CloudOnly -> 0L
                    }
                }
            }.collect { _items.value = it }
        }
    }

    fun toggleSelection(uri: String) = selection.toggle(uri)

    fun clearSelection() {
        selection.clear()
    }

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedUris(uris: Set<String>) = selection.set(uris)

    /** Seed [albums] from the local album cache so the "Add to album" picker has options. */
    private fun loadAlbums() {
        viewModelScope.launch {
            runCatching { driveRepo.loadAlbumsCached() }
                .onSuccess { _albums.value = it }
        }
    }

    /**
     * Add the selection to album [albumLinkId]: cloud-backed selections join now; LocalOnly ones are
     * queued to upload and join after. Reports (joined now, queued for after) for the snackbar.
     */
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) {
        val userId = primaryUserId ?: return
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
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

    /** Outcome of an upload action, so the screen can word its snackbar. */
    data class UploadOutcome(val queued: Int, val alreadyBackedUp: Int)

    /** Back up every selected LocalOnly photo; already-synced selections are skipped (reported as alreadyBackedUp). */
    fun uploadSelected(onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        val selected = selection.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            // Only LocalOnly items upload; Synced selections are counted as already-backed-up.
            val syncedUris = _items.value
                .filterIsInstance<GalleryItem.Synced>()
                .map { it.local.uri }
                .toSet()
            val toUpload = selected.filter { it !in syncedUris }
            val alreadyBackedUp = selected.size - toUpload.size

            if (toUpload.isNotEmpty()) _backupTarget.value = toUpload.toSet()
            val queued = if (toUpload.isNotEmpty()) {
                forceUploadLocalUris.forceUpload(userId, toUpload)
            } else 0

            selection.clear()
            onResult(UploadOutcome(queued = queued, alreadyBackedUp = alreadyBackedUp))
        }
    }

    private fun localUriOf(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> null
    }

    /** Share the selection to other apps. Device-folder items are local files, so no download step. */
    fun shareSelected() {
        val sel = selection.value
        if (sel.isEmpty()) return
        shareUris(sel.toList())
        selection.clear()
    }

    /** Share specific device photos (by local uri) to other apps — used by the per-cell long-press menu. */
    fun shareUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val uriSet = uris.toSet()
        val items = _items.value.filter { localUriOf(it) in uriSet }
        val parsed = items.mapNotNull { localUriOf(it)?.let(android.net.Uri::parse) }
        if (parsed.isEmpty()) return
        val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(items)
        _shareIntent.tryEmit(eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, parsed, mime))
    }

    // ── Public link for the single selected (local) photo — delegated to the shared
    // [PublicLinkController]. Device-folder photos are always local, so the manage sheet starts at
    // the "upload & create" step. ──────────────────────────────────────────────────────────────────
    val publicLinkState: StateFlow<PublicLinkState> = publicLink.state

    fun singleSelectedLocalUri(): String? = selection.value.takeIf { it.size == 1 }?.first()

    fun resetPublicLinkState() = publicLink.reset()

    fun uploadAndCreateSelectedLink() {
        singleSelectedLocalUri()?.let { publicLink.uploadAndCreate(viewModelScope, it) }
    }

    fun revokePublicLink() = publicLink.revoke(viewModelScope)

    fun setLinkPassword(password: String?) = publicLink.setPassword(viewModelScope, password)

    fun currentPublicLinkUrl(): String? = publicLink.currentUrl()

    /** Back up specific device photos (by local uri) — used by the per-cell long-press menu. */
    fun backUpUris(uris: List<String>, onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val syncedUris = _items.value
                .filterIsInstance<GalleryItem.Synced>()
                .map { it.local.uri }
                .toSet()
            val toUpload = uris.filter { it !in syncedUris }
            val alreadyBackedUp = uris.size - toUpload.size
            val queued = if (toUpload.isNotEmpty()) forceUploadLocalUris.forceUpload(userId, toUpload) else 0
            onResult(UploadOutcome(queued = queued, alreadyBackedUp = alreadyBackedUp))
        }
    }

    /**
     * Back up every photo in this folder. [asMirror] adds the folder to the album-mirror opt-in set first,
     * so uploads also join a matching Drive album; otherwise they just back up to the timeline.
     */
    fun backUpAll(asMirror: Boolean, onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        viewModelScope.launch {
            if (asMirror && bucketName.isNotEmpty()) {
                context.settingsDataStore.edit { p ->
                    val current = p[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] ?: emptySet()
                    p[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = current + bucketName
                }
            }
            val syncedUris = _items.value
                .filterIsInstance<GalleryItem.Synced>()
                .map { it.local.uri }
                .toSet()
            val allLocal = _items.value.mapNotNull { localUriOf(it) }
            val toUpload = allLocal.filter { it !in syncedUris }
            val alreadyBackedUp = allLocal.size - toUpload.size
            if (toUpload.isNotEmpty()) _backupTarget.value = toUpload.toSet()
            val queued = if (toUpload.isNotEmpty()) forceUploadLocalUris.forceUpload(userId, toUpload) else 0
            onResult(UploadOutcome(queued = queued, alreadyBackedUp = alreadyBackedUp))
        }
    }

    /** One-shot system trash/delete consent intent for the screen's IntentSender launcher. */
    private val _pendingDeleteIntent = MutableStateFlow<android.app.PendingIntent?>(null)
    val pendingDeleteIntent: StateFlow<android.app.PendingIntent?> = _pendingDeleteIntent.asStateFlow()

    /** True while a multi-select delete runs, so the screen can block the UI behind a progress drawer. */
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /** Deferred cloud-delete work, held while the system trash dialog is up. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    /** Private vault URIs created by a hide that is waiting on the system delete dialog. Committed to
     *  HIDDEN_PHOTO_URIS once the delete confirms, rolled back if it is cancelled. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /** "privateUri|sourceFolder" entries aligned with [pendingHidePrivateUris], persisted into
     *  HIDDEN_URI_SOURCE_FOLDER_MAP on commit so unhide can return each file to its origin folder. */
    private var pendingHideSourceFolders: List<String> = emptyList()

    /** "privateUri|originalName" entries aligned with [pendingHidePrivateUris], persisted into
     *  HIDDEN_URI_ORIGINAL_NAME_MAP on commit so unhide can restore the original filename. */
    private var pendingHideOriginalNames: List<String> = emptyList()

    /** "privateUri|cloudLinkId" entries for the synced photos in the batch, persisted into
     *  [SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] on commit so unhide can transplant the existing
     *  SyncState row onto the restored URI instead of re-uploading a duplicate Drive entry. */
    private var pendingHideCloudIds: List<String> = emptyList()

    private fun selectedGalleryItems(): List<GalleryItem> {
        val sel = selection.value
        return _items.value.filter { localUriOf(it) in sel }
    }

    /**
     * Delete selected device photos: [freeUpSpace] removes on-device, [deleteFromCloud] also trashes the
     * Drive copy. On Android 11+ the local delete routes through the system trash dialog and defers the cloud delete.
     */
    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val userId = primaryUserId ?: accountManager.getPrimaryUserId().first() ?: return@launch
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

    /**
     * Move the selected photos into the app's Hidden vault or hide them client-side, the same flow the
     * timeline uses ([GalleryViewModel.hideSelected]). A device-backed photo (device-only or synced) is
     * copied into app-private storage and routed through [DeletePhotoUseCase] with `freeUpSpace=true,
     * deleteFromCloud=false, hide=true` so the MediaStore original is removed (one system-delete dialog on
     * Android 11+); a synced photo also stashes its cloud linkId so unhide can re-pair by id. A cloud-only
     * photo has no device file, so it hides client-side by linkId. The Drive copy is never touched.
     */
    fun hideSelected() {
        val items = selectedGalleryItems()
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
        viewModelScope.launch {
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
                // Step 1: copy each device file into app-private hidden storage. A synced photo also
                // stashes its cloud linkId so unhide can re-pair by id instead of re-uploading.
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
                    // A null privateUri means store() failed; it already logged a privacy-safe reason.
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

                // Step 2: delete the MediaStore originals (one system-delete dialog on Android 11+).
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

    /** Persist the pending hide URIs into HIDDEN_PHOTO_URIS so they survive a restart and the load()
     *  filter keeps them out of the folder. */
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

    /** Discard private copies that were created but never committed (error or cancelled delete). */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        pendingHideSourceFolders = emptyList()
        pendingHideOriginalNames = emptyList()
        pendingHideCloudIds = emptyList()
        for (u in uris) hiddenStorage.delete(u)
    }

    /** Run the deferred cloud delete once the system trash dialog is confirmed, then clear. A hide
     *  flow commits its vault copies here once the local delete is confirmed. */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        viewModelScope.launch {
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
                    if (pending.hide) {
                        // Snapshot before commitPendingHide() clears the pending list, then offer Undo.
                        val hideUris = pendingHidePrivateUris
                        commitPendingHide()
                        buildHideUndoAction(hideUris)?.let { undoController.offer(it) }
                    } else {
                        // The system trash keeps the local files for ~30 days, so a confirmed delete is
                        // reversible: localRecoverable = true.
                        buildDeleteUndoAction(
                            pending.itemsBeingDeleted,
                            pending.freeUpSpace,
                            deleteFromCloud = pending.cloudLinkIds.isNotEmpty(),
                            hide = false,
                            localRecoverable = true,
                        )?.let { undoController.offer(it) }
                    }
                }
            }
            selection.clear()
        }
    }

    /** User cancelled the system trash dialog — drop the deferred cloud work and any pending vault copies. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        rollbackPendingHide()
    }

    // ── Batch EXIF strip (+ Android 11+ write-permission handshake) ─────────────────────────────
    // Every folder item is a device file, so the whole selection is strippable (no cloud-only skip),
    // mirroring the timeline's More -> Strip metadata so the two look and behave the same.
    private val _multiStripState = MutableStateFlow<MultiStripState>(MultiStripState.Idle)
    val multiStripState: StateFlow<MultiStripState> = _multiStripState.asStateFlow()

    private val _pendingStripIntent = MutableStateFlow<android.app.PendingIntent?>(null)
    val pendingStripIntent: StateFlow<android.app.PendingIntent?> = _pendingStripIntent.asStateFlow()

    private var pendingStripConfig: MetadataStripConfig? = null
    private var pendingStripUris: List<String> = emptyList()
    private var pendingStripStripped = 0
    private var pendingStripSkipped = 0

    fun stripMetadataSelected() {
        val uris = selection.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
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
            runStripPass(config, uris, baseStripped = 0, baseSkipped = 0)
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
                context.contentResolver, needsPermission.map(android.net.Uri::parse),
            )
            _multiStripState.value = MultiStripState.Idle
            return
        }
        selection.clear()
        _multiStripState.value = MultiStripState.Done(totalStripped, totalSkipped + needsPermission.size)
    }

    fun onStripPermissionGranted() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        clearPendingStripState()
        _pendingStripIntent.value = null
        viewModelScope.launch {
            _multiStripState.value = MultiStripState.Working
            runStripPass(config, uris, baseStripped, baseSkipped)
        }
    }

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
}
