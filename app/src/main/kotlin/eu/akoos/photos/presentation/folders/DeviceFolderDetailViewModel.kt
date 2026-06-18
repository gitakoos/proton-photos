/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
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
    private val driveRepo: DrivePhotoRepository,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
) : ViewModel() {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    /** Cloud albums the selection can be added to. Seeded once from the local DB cache so the
     *  "Add to album" picker has albums to show without a network round-trip. */
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /** URIs the user has selected. Selection mode is active whenever this is non-empty. */
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

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

    /** Cancel an in-flight folder back-up — stops the upload worker and clears progress. */
    fun cancelBackup() {
        eu.akoos.photos.worker.SyncWorker.cancel(androidx.work.WorkManager.getInstance(context))
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

    fun toggleSelection(uri: String) {
        _selectedUris.update { if (uri in it) it - uri else it + uri }
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedUris(uris: Set<String>) {
        _selectedUris.value = uris
    }

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

            _selectedUris.value = emptySet()
            onResult(joined, queued)
        }
    }

    /** Outcome of an upload action, so the screen can word its snackbar. */
    data class UploadOutcome(val queued: Int, val alreadyBackedUp: Int)

    /** Back up every selected LocalOnly photo; already-synced selections are skipped (reported as alreadyBackedUp). */
    fun uploadSelected(onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        val selected = _selectedUris.value
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

            _selectedUris.value = emptySet()
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
        val sel = _selectedUris.value
        if (sel.isEmpty()) return
        shareUris(sel.toList())
        _selectedUris.value = emptySet()
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

    fun singleSelectedLocalUri(): String? = _selectedUris.value.takeIf { it.size == 1 }?.first()

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

    private fun selectedGalleryItems(): List<GalleryItem> {
        val sel = _selectedUris.value
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
                    is DeletePhotoUseCase.Result.Success -> _selectedUris.value = emptySet()
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

    /** Run the deferred cloud delete once the system trash dialog is confirmed, then clear. */
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
                }
            }
            _selectedUris.value = emptySet()
        }
    }

    /** User cancelled the system trash dialog — drop the deferred cloud work. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
    }
}
