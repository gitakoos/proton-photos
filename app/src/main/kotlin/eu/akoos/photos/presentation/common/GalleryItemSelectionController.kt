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
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenCloudPhotos
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.hidden.HiddenVaultDecisions
import eu.akoos.photos.data.hidden.HiddenVaultDiagnostics
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.currentShareStripConfig
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.InvalidateStrippedLocationsUseCase
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.util.ShareFileProvider
import eu.akoos.photos.util.ShareIntentBuilder
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.stripForShareOrOriginal

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
    private val invalidateStrippedLocations: InvalidateStrippedLocationsUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val offlineStore: OfflineStorageManager,
    private val hiddenStorage: HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val transferCenter: TransferCenter,
    private val undoController: UndoController,
    private val accountManager: AccountManager,
    private val favoriteWriter: FavoriteWriter,
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

    /** Why an action did not do what it said, for the screen's snackbar. A hide that cannot fit on
     *  the volume, one that only partly landed, and a delete the cloud refused all reach the user
     *  through here rather than ending in silence. */
    private val _actionFailure = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val actionFailure: SharedFlow<String> = _actionFailure.asSharedFlow()

    /**
     * Raised once when a download begins. Its progress lives on the Activity screen and the
     * selection clears immediately, so without this the screen the user pressed the button on
     * says nothing at all until the whole batch has finished. An event rather than a state: the
     * message is shown once, and a state would have to be reset by whoever read it.
     */
    private val _downloadStarted = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val downloadStarted: SharedFlow<Int> = _downloadStarted.asSharedFlow()

    /** Cloud linkIds pinned for offline. Read here to decide which way [toggleSelectedOffline] goes,
     *  and by the surface to name that direction on the row the user presses. */
    val offlinePinIds: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // ── Favourite ───────────────────────────────────────────────────────────────────────────────

    /** The device-side favourite set, which the dock needs to decide which way its button goes. */
    val favoriteIds: StateFlow<Set<String>> =
        favoriteWriter.favoriteIds.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _favoriteState = MutableStateFlow<FavoriteActionState>(FavoriteActionState.Idle)
    val favoriteState: StateFlow<FavoriteActionState> = _favoriteState.asStateFlow()

    /**
     * Puts the whole selection into the state [favoriteTurnsOn] picks for it: on if any selected photo
     * is not a favourite yet, off once they all are.
     *
     * The selection is kept rather than cleared. This is the one action here that is a toggle whose
     * direction the button itself shows, so holding the selection is what lets a second press take
     * back the first, and the button flipping is the confirmation a one-way action gets from its
     * snackbar. Which way the next press goes is read off the selected items, so the ones the write
     * settled are put back carrying their new tag ([withFavoriteSettled]); a photo Drive refused
     * keeps the state it is still in. A clean run says nothing further; a write Drive refused
     * reports through [actionFailure] like every other action that did not do what it said.
     */
    fun toggleSelectedFavorite() {
        val items = selection.value.toList()
        if (items.isEmpty() || _favoriteState.value !is FavoriteActionState.Idle) return
        val turnOn = favoriteTurnsOn(items, favoriteIds.value)
        scope.launch {
            _favoriteState.value = FavoriteActionState.Working(0, items.size)
            val settledIds = HashSet<String>(items.size)
            // The button is guarded on this state, so anything that leaves it Working leaves the
            // button dead for the rest of the session. A write reaching the network can throw, and
            // in a plain launch that also takes the process down, so the release is unconditional.
            val outcome = try {
                favoriteWriter.write(
                    items = items,
                    favorite = turnOn,
                    onProgress = { done ->
                        _favoriteState.value = FavoriteActionState.Working(done, items.size)
                    },
                    onSettled = { settledIds += it.stableId },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                // One pass over the selection once the batch is done rather than one per photo,
                // which on a large selection would be a full rebuild per settled write.
                if (settledIds.isNotEmpty()) {
                    selection.mapEach { withFavoriteSettled(it, settledIds, turnOn) }
                }
                _favoriteState.value = FavoriteActionState.Idle
            } ?: return@launch
            outcome.message()?.let { _actionFailure.tryEmit(it.resolve(context)) }
        }
    }

    // ── Share ───────────────────────────────────────────────────────────────────────────────────
    fun shareSelected() {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            // Null when strip-on-share is off, so each resolved URI passes through untouched below.
            val stripConfig = currentShareStripConfig(context)
            val uris = ArrayList<Uri>(items.size)
            for (item in items) {
                runCatching {
                    val resolved = when (item) {
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
                    val (mime, name) = ShareIntentBuilder.shareMimeAndName(item)
                    stripForShareOrOriginal(context, resolved, mime, name, stripConfig)
                }.onSuccess { uris.add(it) }
                    .onFailure { Log.w(TAG, "share resolve failed: ${it.message}") }
            }
            if (uris.isNotEmpty()) {
                val mime = ShareIntentBuilder.shareableMime(items)
                _shareIntent.tryEmit(ShareIntentBuilder.buildSendIntent(context, uris, mime))
            }
            // A photo that could not be resolved never reaches the chooser, so say how many did.
            shareOutcome(shared = uris.size, failed = items.size - uris.size).message()
                ?.let { _actionFailure.tryEmit(it.resolve(context)) }
            selection.clear()
        }
    }

    // ── Add to album ────────────────────────────────────────────────────────────────────────────
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) {
        val items = selection.value.toList()
        if (items.isEmpty()) return onResult(0, 0)
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch onResult(0, 0)
            val (joined, queued) = addItemsToAlbum(userId, albumLinkId, items)
            selection.clear()
            onResult(joined, queued)
        }
    }

    /**
     * Create a cloud album named [name] and add the selection to it, the two steps the picker's
     * "New album" row stands for. Reports the same (joined, queued) pair the add to an existing
     * album does, plus the reason when the album could not be created.
     */
    fun createAlbumThenAddSelected(
        name: String,
        onResult: (joined: Int, queued: Int, error: String?) -> Unit,
    ) {
        val trimmed = ProtonPhotosStorage.sanitize(name)
        if (trimmed.isEmpty()) return onResult(0, 0, context.getString(R.string.albums_name_empty))
        val items = selection.value.toList()
        if (items.isEmpty()) return onResult(0, 0, null)
        scope.launch {
            val userId = accountManager.getPrimaryUserId().first()
                ?: return@launch onResult(0, 0, context.getString(R.string.viewer_not_signed_in))
            val albumLinkId = try {
                driveRepo.createDriveAlbum(userId, trimmed).linkId
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onResult(0, 0, context.getString(R.string.gallery_create_album_failed, e.message ?: ""))
                return@launch
            }
            val (joined, queued) = addItemsToAlbum(userId, albumLinkId, items)
            selection.clear()
            onResult(joined, queued, null)
        }
    }

    /** The one add body both album routes share: cloud-backed photos join now, device-only ones are
     *  queued to upload and join after. Returns (joined now, queued for after). */
    private suspend fun addItemsToAlbum(
        userId: UserId,
        albumLinkId: String,
        items: List<GalleryItem>,
    ): Pair<Int, Int> {
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
        return joined to queued
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

    // ── Download (cloud items to DCIM/Camera, cloud-album photos into their album folder) ───────
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
            _downloadStarted.tryEmit(items.size)
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

    /** Private vault URIs of a hide whose intent is journalled and whose system delete has not
     *  confirmed yet. Published into HIDDEN_PHOTO_URIS once it does, discarded if it is cancelled. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /** The client-side half of the same in-flight hide, held so the Undo offered once the delete
     *  confirms reverses the whole hide rather than only the photos that were vaulted. */
    private var pendingHideCloudLinkIds: List<String> = emptyList()

    /** How many device files that hide could not copy into the vault. Carried to whichever commit
     *  path lands so the count is reported once the hide is actually done. */
    private var pendingHideFailures = 0

    /**
     * The two halves the current selection's hide would act on, for the confirmation that fronts it.
     *
     * The very split [hideSelected] runs on, read from the same selection, so the sheet describes
     * exactly what the Hide button is about to do rather than what hiding does in general.
     */
    fun hideSplitForSelection(): HiddenFolderRecords.HideSplit =
        HiddenFolderRecords.hideSplit(selection.value.toList())

    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        scope.launch {
            // A local (device) delete needs no account; the use case only requires a signed-in user
            // for a cloud trash, which local-only mode never produces. Pass the nullable userId
            // through instead of silently dropping a guest delete here.
            val userId = accountManager.getPrimaryUserId().first()
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
                    is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                        // The Drive copies stayed where they were, so the photos are still there to
                        // act on: say why and hand the selection back rather than leaving a bar the
                        // user has to guess about.
                        _actionFailure.tryEmit(context.getString(R.string.viewer_delete_drive_failed))
                        selection.clear()
                    }
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    /**
     * Hide the selection: every photo with a device file moves into the vault, and a cloud-only one
     * hides by its linkId.
     *
     * The intent is journalled BEFORE the MediaStore delete and confirmed after it, so an
     * interruption between the two leaves a repairable record instead of bytes nothing refers to —
     * see [HiddenVaultJournal].
     */
    fun hideSelected() {
        val items = selection.value.toList()
        if (items.isEmpty()) return
        // Both halves come from the one shared decision, so this surface routes a photo exactly as
        // the timeline and the folder screens do: a photo with a device file moves into the vault
        // carrying whatever Drive pairing it has, and a cloud-only one, which has no file to move,
        // hides by its cloud linkId. Nothing on Drive changes either way.
        val split = HiddenFolderRecords.hideSplit(items)
        val vaultable = split.vaultable
        scope.launch {
            _isDeleting.value = true
            try {
                HiddenVaultDiagnostics.hideStarted(split)
                HiddenCloudPhotos.hide(context, split.cloudLinkIds)
                pendingHideCloudLinkIds = split.cloudLinkIds
                pendingHideFailures = 0
                if (vaultable.isEmpty()) {
                    // Pure cloud-only selection: the client-side hide above is the whole operation,
                    // and it is offered on the Undo bar exactly as a vaulting hide is, so the same
                    // button stays reversible whichever kind of photo it was pressed on.
                    pendingHideCloudLinkIds = emptyList()
                    buildHideUndoAction(emptyList(), split.cloudLinkIds)?.let { undoController.offer(it) }
                    selection.clear()
                    return@launch
                }
                // Refuse up front when the copies cannot fit. A hide holds both the originals and the
                // vault copies at once, so a volume that runs out mid-batch fails per file with
                // nothing the user can act on.
                val shortfall = hiddenVaultJournal.spaceShortfallBytes(vaultable.sumOf { it.sizeBytes })
                if (shortfall > 0L) {
                    rollbackPendingHide()
                    _actionFailure.tryEmit(
                        context.getString(R.string.gallery_hide_needs_free_space, formatBytes(shortfall)),
                    )
                    return@launch
                }
                // Copy every device file into app-private hidden storage, a backed-up photo stashing
                // its cloud linkId so the reveal re-pairs it rather than uploading a second copy. A
                // file that could not be copied is counted rather than dropped: its photo stays
                // visible, so a hide that reported plain success would be describing a state the user
                // can see is not true.
                val collected = mutableListOf<HiddenVaultJournal.Entry>()
                var hideFailures = 0
                for (target in vaultable) {
                    val local = target.local
                    val sourceFolder = hiddenStorage.sourceFolderFor(local.uri, local.bucketName)
                    val privateUri = hiddenStorage.store(
                        local.uri, local.displayName, local.mimeType, captureTimeMs = target.captureTimeMs,
                    )
                    if (privateUri != null) {
                        collected += HiddenVaultJournal.Entry(
                            privateUri = privateUri,
                            sourceUri = local.uri,
                            sourceFolder = sourceFolder,
                            originalName = local.displayName,
                            cloudLinkId = target.cloudLinkId,
                        )
                    } else {
                        // store() already logged the reason (privacy-safe, no file name).
                        hideFailures++
                    }
                }
                HiddenVaultDiagnostics.copied(collected.size, hideFailures)
                if (collected.isEmpty()) {
                    rollbackPendingHide()
                    _actionFailure.tryEmit(context.getString(R.string.gallery_copy_to_hidden_failed))
                    return@launch
                }
                // Record the intent BEFORE anything is deleted, so an interruption during the delete
                // leaves a recoverable state rather than orphaned bytes.
                if (!hiddenVaultJournal.journal(collected)) {
                    hiddenVaultJournal.discard(collected.map { it.privateUri })
                    rollbackPendingHide()
                    _actionFailure.tryEmit(context.getString(R.string.gallery_move_to_hidden_failed))
                    return@launch
                }
                pendingHidePrivateUris = collected.map { it.privateUri }
                pendingHideFailures = hideFailures

                // Delete the MediaStore originals of exactly what was copied. Narrowing to the copied
                // files is what leaves a photo whose copy failed where the user can still see it: this
                // delete is permanent, so passing the whole selection would take it nowhere.
                val deleting = HiddenVaultDecisions.deletableOriginals(vaultable, collected)
                // Hiding a device photo needs no account; the vault is app-private and the use case
                // only requires a signed-in user for a cloud trash, which a hide never performs. Pass
                // the nullable userId through instead of aborting a guest hide here.
                val userId = accountManager.getPrimaryUserId().first()
                when (val result = deletePhotoUseCase(userId, deleting, freeUpSpace = true, deleteFromCloud = false, hide = true)) {
                    is DeletePhotoUseCase.Result.Success -> {
                        HiddenVaultDiagnostics.originalsRemoved(deleting.size, neededConsent = false)
                        // Snapshot both halves before commitPendingHide() clears the pending list, so
                        // Undo reverses exactly the hide that just landed.
                        val hideUris = pendingHidePrivateUris
                        val hideCloudIds = pendingHideCloudLinkIds
                        pendingHideCloudLinkIds = emptyList()
                        commitPendingHide()
                        buildHideUndoAction(hideUris, hideCloudIds)?.let { undoController.offer(it) }
                        reportHideFailures()
                        selection.clear()
                    }
                    is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                        HiddenVaultDiagnostics.originalsAwaitingConsent(deleting.size)
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

    /** Publish the journalled hide now that the delete has confirmed. */
    private suspend fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        hiddenVaultJournal.confirm(uris)
    }

    /** Say how many photos a landed hide left behind, and only then: a photo whose copy failed is
     *  still on the device, so a hide that reported plain success would contradict the grid. */
    private fun reportHideFailures() {
        val failures = pendingHideFailures
        pendingHideFailures = 0
        if (failures <= 0) return
        _actionFailure.tryEmit(
            context.resources.getQuantityString(R.plurals.gallery_hide_partial_failed, failures, failures),
        )
    }

    /** Undo a hide that did not land: drop the copies and everything journalled for them, and put the
     *  cloud-only half back in every listing. Those ids are written before the device half is even
     *  attempted, so leaving them set is what made a refused hide still take photos away. */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        val cloudIds = pendingHideCloudLinkIds
        pendingHidePrivateUris = emptyList()
        pendingHideCloudLinkIds = emptyList()
        pendingHideFailures = 0
        if (uris.isEmpty() && cloudIds.isEmpty()) return
        scope.launch {
            if (uris.isNotEmpty()) hiddenVaultJournal.discard(uris)
            HiddenCloudPhotos.reveal(context, cloudIds)
        }
    }

    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        scope.launch {
            if (pending != null) {
                // A local (device) delete/hide finishes without an account; the use case guards its
                // own cloud branch. run {} instead of an if-null so a guest's confirmed action commits.
                val userId = accountManager.getPrimaryUserId().first()
                run {
                    // The refusal comes back as an answer rather than an exception, so it was
                    // being dropped: the device file had gone, the Drive copy had not, and the
                    // screen said nothing at all. The timeline surface already reports this.
                    val cloudResult = deletePhotoUseCase.completeAfterPermissionGranted(
                        userId = userId,
                        cloudLinkIds = pending.cloudLinkIds,
                        items = pending.itemsBeingDeleted,
                        freeUpSpace = pending.freeUpSpace,
                        hide = pending.hide,
                    )
                    if (cloudResult is DeletePhotoUseCase.Result.CloudDeleteFailed) {
                        _actionFailure.tryEmit(context.getString(R.string.viewer_delete_drive_failed))
                    }
                    // Snapshot both halves of the hide before commit clears them, then offer Undo for
                    // whichever reversible action landed.
                    val hideUris = pendingHidePrivateUris
                    val hideCloudIds = pendingHideCloudLinkIds
                    pendingHideCloudLinkIds = emptyList()
                    if (pending.hide) {
                        commitPendingHide()
                        reportHideFailures()
                    }
                    val undo: UndoAction? = when {
                        pending.hide && hideUris.isNotEmpty() -> buildHideUndoAction(hideUris, hideCloudIds)
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
    private var pendingStripFailed = 0

    /** [config] comes straight from the multi-select picker, so this manual strip is independent of
     *  the upload-strip settings. An empty config never reaches here (the picker's confirm is gated on
     *  at least one field), so it is a silent no-op rather than a surfaced error. */
    fun stripMetadataSelected(config: MetadataStripConfig) {
        val items = selection.value.toList()
        if (items.isEmpty() || config.isNoOp) return
        scope.launch {
            _multiStripState.value = MultiStripState.Working
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
            runStripPass(config, localUris, baseStripped = 0, baseSkipped = skippedCloud, baseFailed = 0)
        }
    }

    private suspend fun runStripPass(
        config: MetadataStripConfig,
        uris: List<String>,
        baseStripped: Int,
        baseSkipped: Int,
        baseFailed: Int,
    ) {
        val needsPermission = mutableListOf<String>()
        // The URIs that took the strip, not just a count: each one is a live MediaStore file whose
        // stored GPS fix a location strip makes stale. A deferred URI keeps its fix until the retry
        // pass strips it and lands here itself.
        val strippedUris = mutableListOf<String>()
        val failed = withContext(Dispatchers.IO) {
            var failedCount = 0
            for (uri in uris) {
                when (ExifHelper.stripFieldsInPlace(context, uri, config)) {
                    is StripResult.Stripped        -> strippedUris += uri
                    is StripResult.NeedsPermission  -> needsPermission += uri
                    is StripResult.Failed          -> failedCount++
                }
            }
            failedCount
        }
        invalidateStrippedLocations(config, strippedUris)
        val totalStripped = baseStripped + strippedUris.size
        val totalSkipped  = baseSkipped
        val totalFailed   = baseFailed + failed

        if (needsPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingStripConfig   = config
            pendingStripUris     = needsPermission
            pendingStripStripped = totalStripped
            pendingStripSkipped  = totalSkipped
            pendingStripFailed   = totalFailed
            _pendingStripIntent.value = MediaStore.createWriteRequest(
                context.contentResolver, needsPermission.map(Uri::parse),
            )
            _multiStripState.value = MultiStripState.Idle
            return
        }
        selection.clear()
        _multiStripState.value = terminalStripState(
            totalStripped, totalSkipped + needsPermission.size, totalFailed,
        )
    }

    /** Re-runs the strip on the foreign URIs after the user granted the write-permission dialog. */
    fun onStripPermissionGranted() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        val baseFailed = pendingStripFailed
        clearPendingStripState()
        _pendingStripIntent.value = null
        scope.launch {
            _multiStripState.value = MultiStripState.Working
            runStripPass(config, uris, baseStripped, baseSkipped, baseFailed)
        }
    }

    /** User canceled the write-permission dialog — count the deferred URIs as skipped, no retry. */
    fun clearPendingStripIntent() {
        val baseStripped = pendingStripStripped
        val deferred = pendingStripUris.size + pendingStripSkipped
        val baseFailed = pendingStripFailed
        clearPendingStripState()
        _pendingStripIntent.value = null
        selection.clear()
        _multiStripState.value = terminalStripState(baseStripped, deferred, baseFailed)
    }

    /** A file the strip tried and could not write reads as a failure, not as a deliberate skip. */
    private fun terminalStripState(stripped: Int, skipped: Int, failed: Int): MultiStripState =
        when (val outcome = stripOutcome(stripped, skipped, failed)) {
            is StripOutcome.Done -> MultiStripState.Done(outcome.stripped, outcome.skipped)
            is StripOutcome.Failed -> MultiStripState.Failed(outcome.message().resolve(context))
        }

    private fun clearPendingStripState() {
        pendingStripConfig = null
        pendingStripUris = emptyList()
        pendingStripStripped = 0
        pendingStripSkipped = 0
        pendingStripFailed = 0
    }

    fun resetMultiStripState() {
        _multiStripState.value = MultiStripState.Idle
    }

    private companion object {
        const val TAG = "SelectionCtl"
    }
}
