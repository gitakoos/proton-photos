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
import javax.inject.Inject

/**
 * Backs [DeviceFolderDetailScreen]: the device-resident photos of one MediaStore bucket, with
 * uri-keyed selection and an "upload selected to Drive" action. The grid shows only photos that
 * physically live on the device (LocalOnly + Synced) in this bucket — a CloudOnly entry has no
 * device file to upload, so it never appears here. The sync badge on each cell reflects the live
 * [eu.akoos.photos.domain.entity.SyncState], so a freshly uploaded photo flips to the green-cloud
 * badge on its own.
 */
@HiltViewModel
class DeviceFolderDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val driveRepo: DrivePhotoRepository,
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

    /**
     * `done / total` for the active back-up, or null when none is running. Derived from the same
     * live item stream the grid observes: a queued photo counts as done once its row flips to
     * [GalleryItem.Synced], so the bar fills as uploads land without any extra pipeline plumbing.
     */
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

    /**
     * Observe device photos in [bucketName], newest first, excluding hidden-vault items (same
     * DataStore key the gallery uses). CloudOnly items are dropped — they have no local file.
     */
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

    /** Seed [albums] from the local album cache so the "Add to album" picker has options. */
    private fun loadAlbums() {
        viewModelScope.launch {
            runCatching { driveRepo.loadAlbumsCached() }
                .onSuccess { _albums.value = it }
        }
    }

    /**
     * Add the current selection to the cloud album [albumLinkId]. Synced (and, defensively,
     * CloudOnly) selections carry a Drive linkId and join the album immediately; LocalOnly
     * selections have no linkId yet, so they are queued to back up and join afterwards. Clears
     * the selection and reports `(joined, queued)` so the screen can word its snackbar — [joined]
     * is how many cloud photos joined now, [queued] how many local photos will join after upload.
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

    /**
     * Force every selected LocalOnly photo to back up to Drive; already-synced selections are
     * skipped (they are reported as [UploadOutcome.alreadyBackedUp]). Clears the selection and
     * hands the count back via [onResult] so the caller can show a snackbar.
     */
    fun uploadSelected(onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        val selected = _selectedUris.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            // Only LocalOnly items need uploading. A Synced selection is already on Drive, so it
            // is counted as already-backed-up and left alone.
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

    /**
     * Share the selected photos to other apps via the system chooser. Device-folder items are
     * always local files, so the content URIs are shareable directly with no download step.
     */
    fun shareSelected() {
        val sel = _selectedUris.value
        if (sel.isEmpty()) return
        shareUris(sel.toList())
        _selectedUris.value = emptySet()
    }

    /**
     * Share specific device photos (keyed by local uri) to other apps — used by the per-cell
     * long-press menu. Resolves the matching items to content URIs and emits the send intent.
     */
    fun shareUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val uriSet = uris.toSet()
        val items = _items.value.filter { localUriOf(it) in uriSet }
        val parsed = items.mapNotNull { localUriOf(it)?.let(android.net.Uri::parse) }
        if (parsed.isEmpty()) return
        val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(items)
        _shareIntent.tryEmit(eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, parsed, mime))
    }

    /**
     * Force-upload specific device photos (keyed by local uri) to Drive — used by the per-cell
     * long-press menu. Already-synced uris are skipped and reported as [UploadOutcome.alreadyBackedUp].
     */
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
     * Back up every photo in this folder to Drive. When [asMirror] is true the folder is added to
     * the album-mirror opt-in set first, so the upload pipeline creates a matching Drive album and
     * each uploaded photo joins it; otherwise the photos just back up to the timeline. Already-synced
     * photos are skipped and reported as [UploadOutcome.alreadyBackedUp].
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

    /** Deferred cloud-delete work, held while the system trash dialog is up. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    private fun selectedGalleryItems(): List<GalleryItem> {
        val sel = _selectedUris.value
        return _items.value.filter { localUriOf(it) in sel }
    }

    /**
     * Delete the selected device photos. [freeUpSpace] removes the on-device copy; [deleteFromCloud]
     * also removes the Drive copy of any backed-up (Synced) selection. On Android 11+ a local delete
     * routes through the system trash dialog first ([DeletePhotoUseCase]); the cloud delete is
     * deferred until the user confirms. The grid empties itself as the deleted rows leave the
     * observed media stream, so there is no separate "done" state to clear.
     */
    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val userId = primaryUserId ?: accountManager.getPrimaryUserId().first() ?: return@launch
            when (val result = deletePhotoUseCase(userId, items, freeUpSpace, deleteFromCloud)) {
                is DeletePhotoUseCase.Result.Success -> _selectedUris.value = emptySet()
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    _pendingDeleteIntent.value = result.pendingIntent
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> Unit
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
