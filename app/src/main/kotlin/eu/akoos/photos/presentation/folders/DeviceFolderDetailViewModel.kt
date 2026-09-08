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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.folders

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.hidden.HiddenCloudPhotos
import eu.akoos.photos.data.hidden.HiddenFolderProgress
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.data.hidden.HiddenVaultDecisions
import eu.akoos.photos.data.hidden.HiddenVaultDiagnostics
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.hidden.HiddenVaultRecords
import eu.akoos.photos.data.hidden.HiddenVaultRestorer
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.stripForShareOrOriginal
import eu.akoos.photos.presentation.albums.AlbumPhotoSortMode
import eu.akoos.photos.presentation.common.FavoriteActionState
import eu.akoos.photos.presentation.common.MoveToFolderController
import eu.akoos.photos.presentation.common.MultiStripState
import eu.akoos.photos.presentation.common.PhotoSortOrder
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.StripOutcome
import eu.akoos.photos.presentation.common.message
import eu.akoos.photos.presentation.common.stripOutcome
import android.provider.MediaStore
import android.os.Build
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import eu.akoos.photos.data.preferences.currentShareStripConfig
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.InvalidateStrippedLocationsUseCase
import eu.akoos.photos.presentation.common.SelectionState
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.presentation.common.buildDeleteUndoAction
import eu.akoos.photos.presentation.common.buildHideUndoAction
import eu.akoos.photos.presentation.common.withFavoriteSettled
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
    private val invalidateStrippedLocations: InvalidateStrippedLocationsUseCase,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val hiddenStorage: eu.akoos.photos.data.hidden.HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val hiddenVaultRestorer: HiddenVaultRestorer,
    private val localMediaRepo: LocalMediaRepository,
    private val driveRepo: DrivePhotoRepository,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val upload: eu.akoos.photos.domain.usecase.UploadPendingUseCase,
    private val undoController: UndoController,
    private val favoriteWriter: eu.akoos.photos.presentation.common.FavoriteWriter,
    private val moveController: MoveToFolderController,
) : ViewModel() {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    /** Which of [items] are in the vault rather than on the device. They show and open like the rest,
     *  but there is no device file behind them, so every action that needs one is offered on the
     *  others alone and these are revealed instead. */
    private val _vaultedUris = MutableStateFlow<Set<String>>(emptySet())
    val vaultedUris: StateFlow<Set<String>> = _vaultedUris.asStateFlow()

    /** The vaulted photos on this screen that still have a Drive copy, so their tiles carry the same
     *  green cloud the timeline puts on a backed-up photo. Vaulting takes the MediaStore row away, so
     *  the photo reaches the grid as a device-only item and only the vault's records can still say a
     *  Drive copy is there. */
    private val _pairedVaultUris = MutableStateFlow<Set<String>>(emptySet())
    val pairedVaultUris: StateFlow<Set<String>> = _pairedVaultUris.asStateFlow()

    /** Cloud albums the selection can be added to. Seeded once from the local DB cache so the
     *  "Add to album" picker has albums to show without a network round-trip. */
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /** Whether a Proton account is signed in. A local-only session has no Drive, so the selection
     *  bar's cloud actions and the folder drawer's cloud rows drop out. Defaults to signed-in so
     *  nothing flickers before the first emit. */
    val isSignedIn: StateFlow<Boolean> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** URIs the user has selected. Selection mode is active whenever this is non-empty. */
    private val selection = SelectionState<String>()
    val selectedUris: StateFlow<Set<String>> = selection.flow

    /** Cloud linkIds pinned for offline; drives the per-cell offline badge (a Synced folder item
     *  whose cloud twin is pinned). Backed by the same OFFLINE_PIN_IDS pref the timeline reads. */
    val offlinePinIds: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The device-side favourite set: the per-cell heart, and which way the dock's button goes. */
    val favoriteIds: StateFlow<Set<String>> =
        favoriteWriter.favoriteIds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _favoriteState = MutableStateFlow<FavoriteActionState>(FavoriteActionState.Idle)
    val favoriteState: StateFlow<FavoriteActionState> = _favoriteState.asStateFlow()

    /** Why a batch favourite did not reach every photo it was pressed for, for the screen's snackbar. */
    private val _favoriteFailure = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val favoriteFailure: SharedFlow<String> = _favoriteFailure.asSharedFlow()

    /**
     * Puts every selected photo into the state [favoriteTurnsOn] picks for it: on if any of them is
     * not a favourite yet, off once they all are.
     *
     * The selection is kept rather than cleared, so a second press takes the first one back and the
     * button flipping is the confirmation. The folder's rows are a one-shot load, so each photo the
     * write settled has its tag re-stated here ([withFavoriteSettled]) and both the cell's heart and
     * the direction of the next press follow the write. A photo Drive refused is left as it is.
     */
    fun toggleSelectedFavorite() {
        val selected = selection.value
        val items = _items.value.filter { itemUriOf(it) in selected }
        if (items.isEmpty() || _favoriteState.value !is FavoriteActionState.Idle) return
        val turnOn = favoriteTurnsOn(items, favoriteIds.value)
        viewModelScope.launch {
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
                // One pass over the folder once the batch is done rather than one per photo, which
                // on a large folder would be a full rebuild per settled write.
                if (settledIds.isNotEmpty()) {
                    _items.update { rows -> rows.map { withFavoriteSettled(it, settledIds, turnOn) } }
                }
                _favoriteState.value = FavoriteActionState.Idle
            } ?: return@launch
            outcome.message()?.let { _favoriteFailure.tryEmit(it.resolve(context)) }
        }
    }

    /** The key this screen selects a photo by: its device uri. A cloud-only row has none and is
     *  never selectable here. */
    private fun itemUriOf(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced    -> item.local.uri
        is GalleryItem.CloudOnly -> null
    }

    private var primaryUserId: UserId? = null

    /** The folder this screen is showing, as a flow so the per-folder preference states can key on it. */
    private val bucketName = MutableStateFlow("")

    /**
     * True where this screen was opened from the vault's card for the folder rather than from the
     * Albums grid's, which is what decides everything the two sides disagree on: the photo list, and
     * whether the drawer's hide row reveals or hides.
     *
     * The folder's own hidden state cannot decide it. A folder the vault holds only part of has a
     * card on each side at once, and both would read that state as true.
     */
    private val openedFromVault = MutableStateFlow(false)

    /** True while this folder's photos also join a matching Drive album as they upload. */
    val isMirroredAsAlbum: StateFlow<Boolean> = folderFlag(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES)

    /** True while this folder is carved out of "Back up everything". */
    val isExcludedFromBackup: StateFlow<Boolean> = folderFlag(SettingsKeys.EXCLUDED_FOLDER_NAMES)

    /** True while this folder's photos are kept out of the main timeline (display only). */
    val isHiddenFromTimeline: StateFlow<Boolean> = folderFlag(SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES)

    /** True while the photos this screen lists are the vault's, which is also when the drawer's hide
     *  row reveals them. False on the device side even for a folder the vault holds photos from, so
     *  a row that would put more away never shows as already done. The weaker [isHiddenFromTimeline]
     *  only keeps a folder's photos out of the main feed. */
    val isHiddenCard: StateFlow<Boolean> =
        combine(folderFlag(SettingsKeys.HIDDEN_FOLDER_NAMES), openedFromVault) { hidden, fromVault ->
            hidden && fromVault
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Direction this folder lists its photos in, read straight from preferences so a change lands
     *  on the grid without a reload. Shares the album's type: the choice is the same two directions
     *  over the same capture time, and a second enum would fork the labels and icons with it. */
    private val sortModeFlow = context.settingsDataStore.data
        .map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE]) }

    /** The same value for the actions sheet, which only has to say which direction is ticked. */
    val sortMode: StateFlow<AlbumPhotoSortMode> = sortModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumPhotoSortMode.Default)

    /** Persist a direction. Every folder shares the one choice, matching how the album stores its. */
    fun setSortMode(mode: AlbumPhotoSortMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE] = mode.ordinal }
        }
    }

    /** The photo this folder's cover is pinned to, or null while it follows the first photo of the
     *  current order. The hero still falls back when the pinned photo is not among the folder's
     *  current items. */
    val pinnedCoverUri: StateFlow<String?> =
        combine(context.settingsDataStore.data, bucketName) { prefs, name ->
            if (name.isEmpty()) null
            else FolderCoverMap.parse(prefs[SettingsKeys.FOLDER_COVER_URI_MAP])[name]
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pin the single selected photo as this folder's cover and drop the selection. Mirrors the
     *  album's cover action, but the choice lives in a preference: a device folder is a MediaStore
     *  bucket, which carries nothing of its own to store a cover on.
     *
     *  [FolderCoverSelection] answers which photo qualifies, so a hidden one is refused here as well
     *  as on the row that offers the action. */
    fun setSelectedAsFolderCover(onDone: () -> Unit) {
        val name = bucketName.value
        val uri = FolderCoverSelection.pinnable(selection.flow.value, _vaultedUris.value)
        if (name.isEmpty() || uri.isNullOrEmpty()) return
        viewModelScope.launch {
            runCatching {
                context.settingsDataStore.edit { p ->
                    p[SettingsKeys.FOLDER_COVER_URI_MAP] =
                        FolderCoverMap.withCover(p[SettingsKeys.FOLDER_COVER_URI_MAP] ?: emptySet(), name, uri)
                }
            }.onSuccess {
                selection.clear()
                onDone()
            }
        }
    }

    /** Whether the current folder's name is in [key]'s set. Every per-folder preference keys on the
     *  bucket name, matching the Settings pickers. */
    private fun folderFlag(
        key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>,
    ): StateFlow<Boolean> = combine(context.settingsDataStore.data, bucketName) { prefs, name ->
        name.isNotEmpty() && name in (prefs[key] ?: emptySet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The one writer for this screen's per-folder preference sets. [enable] null flips the stored
     *  state; reading and writing inside the same edit keeps a flip atomic. */
    private suspend fun setFolderFlag(
        key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>,
        enable: Boolean?,
    ) {
        val name = bucketName.value
        if (name.isEmpty()) return
        context.settingsDataStore.edit { p ->
            val current = p[key] ?: emptySet()
            val on = enable ?: (name !in current)
            p[key] = if (on) current + name else current - name
        }
    }

    /** Opt this folder in or out of also surfacing as a Drive album. */
    fun toggleMirrorAsAlbum() {
        viewModelScope.launch { setFolderFlag(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES, enable = null) }
    }

    /** Carve this folder out of "Back up everything", or put it back in. */
    fun toggleExcludedFromBackup() {
        viewModelScope.launch {
            setFolderFlag(SettingsKeys.EXCLUDED_FOLDER_NAMES, enable = null)
            // Same follow-up the Settings picker runs: a fresh reconcile drops rows that just landed
            // in the excluded set before an in-flight sync pass can upload them.
            val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
            eu.akoos.photos.worker.SyncWorker.runNow(context, wifiOnly)
        }
    }

    /** Show or hide this folder's photos in the main timeline. Display only, so nothing to reconcile —
     *  the gallery observes the key and re-filters on the next emission. */
    fun toggleHiddenFromTimeline() {
        viewModelScope.launch {
            setFolderFlag(SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES, enable = null)
        }
    }

    /**
     * Put this screen's photos into the vault, or take them back out of it. A folder the vault holds
     * entirely has no card on the Albums grid left to open, so it is genuinely put away rather than
     * merely unlisted.
     *
     * Which direction runs follows [openedFromVault] — the side the user came in from — rather than
     * the folder's stored hidden state, which is true of both sides at once for a folder the vault
     * holds only part of. The device side therefore goes on offering the hide for photos taken since
     * an earlier one, and joins them to the folder already in the vault: the name is a set member, so
     * storing it again is the same folder rather than a second one, and the copies land beside what
     * is already there. Only device-only photos are vaulted; synced and cloud-only ones stay filters,
     * which is the routing every hide surface follows.
     *
     * The folder's name is recorded BEFORE the first copy and cleared AFTER the last restore, so a
     * photo in the vault always belongs to a folder that is listed as hidden. A hide the user stops
     * part-way therefore leaves a folder that is hidden, holds what was already copied, and still
     * shows the rest on the device — never a set of vaulted photos with no folder to reach them by.
     */
    fun toggleHiddenCard() {
        if (folderVaultJob?.isActive == true) return
        val name = bucketName.value
        if (name.isEmpty()) return
        val reveal = openedFromVault.value
        stopFolderVault.set(false)
        folderVaultJob = viewModelScope.launch {
            try {
                if (reveal) {
                    val failed = hiddenVaultRestorer.restoreFolder(
                        bucketName = name,
                        onProgress = { done, total ->
                            _folderVault.value = HiddenFolderProgress(done, total, restoring = true)
                        },
                        shouldStop = { stopFolderVault.get() },
                    )
                    if (failed > 0) {
                        _hideFailure.tryEmit(
                            context.resources.getQuantityString(
                                R.plurals.hidden_restore_failed_some, failed, failed,
                            ),
                        )
                    }
                    return@launch
                }
                // The targets are what this screen is showing, which on the device side is the
                // folder's live photos: exactly the ones an earlier hide did not take. The items are
                // already merged, so each one's own type routes it and no pairing lookup is needed.
                val split = HiddenFolderRecords.folderHideSplit(
                    items = _items.value,
                    bucketName = name,
                )
                // The flag goes in before the copies start so a fully-vaulted folder still has a
                // name to show. That makes it one more thing a failed hide has to give back, so the
                // name is held until the hide is known to have landed.
                pendingHideFolderName = name
                setFolderFlag(SettingsKeys.HIDDEN_FOLDER_NAMES, enable = true)
                if (!split.isEmpty) runHide(split, reportProgress = true)
            } finally {
                _folderVault.value = null
            }
        }
    }

    /** Stop a folder hide or restore between photos. Cooperative: the file in transit finishes, and
     *  a hide that stops still journals and deletes the originals of everything already copied, so
     *  those photos are in the vault rather than copied for nothing. */
    fun cancelFolderVault() {
        stopFolderVault.set(true)
    }

    /** One-shot system-share intents emitted to the screen, which launches the chooser. */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    /** Why a hide refused to start, for the screen's snackbar. A hide that cannot fit on the volume,
     *  or cannot record what it is about to delete, has to say so rather than end in silence. */
    private val _hideFailure = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val hideFailure: SharedFlow<String> = _hideFailure.asSharedFlow()

    /** Live progress of an in-flight folder back-up. */
    data class BackupProgress(val done: Int, val total: Int)

    /** URIs queued by the most recent back-up action. Drives [backupProgress]; cleared when every
     *  queued photo has finished uploading (or when the screen leaves). */
    private val _backupTarget = MutableStateFlow<Set<String>>(emptySet())

    /** done/total of a folder moving in or out of the vault, or null when none is. Drives the same
     *  progress pill the back-up uses, so the two long folder operations report the same way. */
    private val _folderVault = MutableStateFlow<HiddenFolderProgress?>(null)
    val folderVault: StateFlow<HiddenFolderProgress?> = _folderVault.asStateFlow()

    /** The running folder hide or restore, so a second tap cannot start a parallel one. */
    private var folderVaultJob: Job? = null

    /** The collector feeding [items], so a second [load] replaces it instead of running beside it and
     *  publishing the two sides of the folder in turn. */
    private var loadJob: Job? = null

    /** Raised by [cancelFolderVault] and polled between photos. A flag rather than a job cancel, so
     *  a stopped hide still records and deletes the originals of everything it already copied. */
    private val stopFolderVault = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * Show [bucketName]. [fromVault] is which card opened this screen, and therefore which of the
     * folder's two sides it answers for — see [openedFromVault].
     */
    fun load(initialBucket: String, fromVault: Boolean = false) {
        this.bucketName.value = initialBucket
        this.openedFromVault.value = fromVault
        loadAlbums()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // The current bucket name rides the records so the filter below follows it: a rename
            // relocates the photos and repoints [bucketName], and this combine re-emits with the new
            // name so the same photos stay listed rather than the screen emptying under the old one.
            val vaultFlow = combine(context.settingsDataStore.data, bucketName) { prefs, name ->
                    VaultRecords(
                        bucketName = name,
                        vaultedUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet(),
                        sourceFolders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet(),
                        originalNames = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet(),
                        pairedUris = HiddenVaultRecords.pairedUris(
                            prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet(),
                        ),
                        isFolderHidden = name in (prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()),
                    )
                }
                .distinctUntilChanged()
            // Re-subscribe on the account so a local-only session (no userId) still lists the device
            // folder's photos, and a later sign-in swaps in the full feed. A signed-in user emits one
            // stable id, so the folder contents are unchanged for them.
            val itemsFlow = accountManager.getPrimaryUserId().flatMapLatest { userId ->
                if (userId != null) getGalleryItems.invoke(userId) else getGalleryItems.invokeLocalOnly()
            }
            combine(
                itemsFlow,
                vaultFlow,
                sortModeFlow,
            ) { all, vault, sort ->
                val visible = all.mapNotNull { item ->
                    val (uri, bucket) = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri to item.local.bucketName
                        is GalleryItem.Synced -> item.local.uri to item.local.bucketName
                        is GalleryItem.CloudOnly -> return@mapNotNull null
                    }
                    if (bucket != vault.bucketName || uri in vault.vaultedUris) return@mapNotNull null
                    item
                }
                // Only the vault side resolves the vault records: on the device side that costs a
                // lookup per record for photos the screen does not list.
                val showsVault = fromVault && vault.isFolderHidden
                val vaulted = if (showsVault) vaultedItemsOf(vault.bucketName, vault) else emptyList()
                // Ordered on captureTimeMs, the value the screen's month headers and the scrubber
                // read. A synced photo's device DATE_TAKEN can differ from its Drive capture time —
                // a downloaded file is dated at download — so ordering on the raw device date would
                // file such a photo under a heading it sorts nowhere near.
                val ordered = PhotoSortOrder.ordered(
                    HiddenFolderRecords.folderPhotos(visible, vaulted, showsVault),
                    newestFirst = sort == AlbumPhotoSortMode.NewestFirst,
                )
                val vaultedUris = vaulted.mapTo(mutableSetOf()) { it.local.uri }
                // Narrowed to what this screen lists, so the badge set stays the size of one folder
                // rather than of the whole vault.
                Triple(ordered, vaultedUris, vaultedUris intersect vault.pairedUris)
            }.collect { (ordered, vaultedUris, pairedUris) ->
                _vaultedUris.value = vaultedUris
                _pairedVaultUris.value = pairedUris
                _items.value = ordered
            }
        }
    }

    /** The vault records this screen reads a folder's contents from, snapshotted together so one
     *  emission carries a consistent view of the sets and of the folder's own hidden state — read
     *  apart, they could describe a hidden folder's contents with the folder still listed as open. */
    private data class VaultRecords(
        val bucketName: String,
        val vaultedUris: Set<String>,
        val sourceFolders: Set<String>,
        val originalNames: Set<String>,
        val pairedUris: Set<String>,
        val isFolderHidden: Boolean,
    )

    /**
     * The photos of [bucketName] that live in the vault rather than on the device.
     *
     * A hidden folder has no MediaStore row left carrying its bucket name, so the gallery feed the
     * list above is built from cannot see a single one of them. Reading them from the vault's own
     * records is what keeps a fully-vaulted folder openable, and it is the same source the folder's
     * card counts from, so the two always agree on what the folder holds.
     *
     * Each vault file lives under a private code, so the name recorded at hide time is put back on it
     * and the bucket is restored, which is what lets the rest of the screen treat these as the
     * folder's photos.
     */
    private suspend fun vaultedItemsOf(bucketName: String, vault: VaultRecords): List<GalleryItem.LocalOnly> {
        val uris = HiddenFolderRecords
            .vaultedByBucket(vault.sourceFolders, vault.vaultedUris)[bucketName]
            .orEmpty()
        if (uris.isEmpty()) return emptyList()
        return uris.mapNotNull { uri ->
            val item = localMediaRepo.queryByUri(uri) ?: return@mapNotNull null
            val original = vault.originalNames.firstOrNull { it.startsWith("$uri|") }?.substringAfter('|')
            GalleryItem.LocalOnly(
                item.copy(
                    bucketName = bucketName,
                    displayName = if (original.isNullOrBlank()) item.displayName else original,
                ),
            )
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
     *
     * A selection with nothing to add — every photo in it is in the vault, so none has a file to
     * upload — still reports, as (0, 0). The caller asked a question and gets an answer either way.
     */
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) {
        val userId = primaryUserId ?: return onResult(0, 0)
        val items = selectedGalleryItems()
        if (items.isEmpty()) return onResult(0, 0)
        viewModelScope.launch {
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
        val userId = primaryUserId ?: return onResult(0, 0, context.getString(R.string.viewer_not_signed_in))
        val items = selectedGalleryItems()
        if (items.isEmpty()) return onResult(0, 0, null)
        viewModelScope.launch {
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

    /** Outcome of an upload action, so the screen can word its snackbar. */
    data class UploadOutcome(val queued: Int, val alreadyBackedUp: Int)

    /** Back up every selected LocalOnly photo; already-synced selections are skipped (reported as alreadyBackedUp). */
    fun uploadSelected(onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        val selected = selectedDeviceUris()
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
        val sel = selectedDeviceUris()
        if (sel.isEmpty()) return
        shareUris(sel)
        selection.clear()
    }

    /** Share specific device photos (by local uri) to other apps — used by the per-cell long-press menu. */
    fun shareUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val uriSet = uris.toSet() - _vaultedUris.value
        val items = _items.value.filter { localUriOf(it) in uriSet }
        if (items.isEmpty()) return
        viewModelScope.launch {
            // Null when strip-on-share is off, so each device URI passes through untouched below.
            val stripConfig = currentShareStripConfig(context)
            val parsed = items.mapNotNull { item ->
                val local = localUriOf(item) ?: return@mapNotNull null
                val (mime, name) = eu.akoos.photos.util.ShareIntentBuilder.shareMimeAndName(item)
                stripForShareOrOriginal(context, android.net.Uri.parse(local), mime, name, stripConfig)
            }
            if (parsed.isEmpty()) return@launch
            val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(items)
            _shareIntent.tryEmit(eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, parsed, mime))
        }
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

    /**
     * Back up every photo in this folder. [asMirror] adds the folder to the album-mirror opt-in set first,
     * so uploads also join a matching Drive album; otherwise they just back up to the timeline.
     */
    fun backUpAll(asMirror: Boolean, onResult: (UploadOutcome) -> Unit) {
        val userId = primaryUserId ?: return
        viewModelScope.launch {
            if (asMirror) setFolderFlag(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES, enable = true)
            val syncedUris = _items.value
                .filterIsInstance<GalleryItem.Synced>()
                .map { it.local.uri }
                .toSet()
            // Vaulted photos are deliberately off Drive's radar, so a folder-wide back-up skips them.
            val allLocal = _items.value.mapNotNull { localUriOf(it) } - _vaultedUris.value
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

    /** Private vault URIs of a hide whose intent is journalled and whose system delete has not
     *  confirmed yet. Published into HIDDEN_PHOTO_URIS once it does, discarded if it is cancelled. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /** The selected photos that still have a device file. A vaulted one is a copy in app-private
     *  storage with no MediaStore row, so there is nothing for a delete, a hide, an album add or a
     *  metadata write to act on until it is revealed. */
    private fun selectedGalleryItems(): List<GalleryItem> {
        val sel = selection.value
        val vaulted = _vaultedUris.value
        return _items.value.filter { localUriOf(it) in sel && localUriOf(it) !in vaulted }
    }

    /** The selected uris that still have a device file, for the actions that take uris rather than
     *  items. */
    private fun selectedDeviceUris(): List<String> = (selection.value - _vaultedUris.value).toList()

    // ── Move to a device folder / rename this folder (logged-out, device data only) ──────────────
    // Delegated to the shared [MoveToFolderController], the same relocation the timeline offers, so a
    // folder detail can send its selection into another DCIM folder or rename itself in place.

    /** Existing device folders offered as move targets, kept warm for the picker. */
    val moveTargetFolders = moveController.targetFolders(viewModelScope)

    /** One-shot system write-consent request a foreign-file move needs; the screen's launcher drives it. */
    val pendingMoveIntent = moveController.pendingMoveIntent

    /** Destination folder of a completed move, for the host's snackbar. */
    val moveConfirmation = moveController.moveConfirmation

    /** Move every selected device photo (device-only + synced, vaulted skipped) into [folderName]. */
    fun moveSelectedToFolder(folderName: String) {
        val uris = selectedDeviceUris()
        moveController.move(viewModelScope, uris, folderName)
        selection.clear()
    }

    /** Move the selection into a freshly named device folder, born with the photos the move lands there. */
    fun createFolderWithPhotos(name: String) {
        val uris = selectedDeviceUris()
        moveController.createFolder(viewModelScope, name, uris)
        selection.clear()
    }

    fun onMovePermissionGranted() = moveController.onPermissionGranted(viewModelScope)

    fun clearPendingMove() = moveController.clearPending()

    /** One-shot confirmation for a completed folder rename, carrying the new name for the snackbar. */
    private val _folderRenameConfirmation = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val folderRenameConfirmation: SharedFlow<String> = _folderRenameConfirmation.asSharedFlow()

    /** Rename this folder by relocating its photos into [newName] under DCIM/. Repointing [bucketName]
     *  keeps the screen on the same photos as they follow into the renamed folder, with no nav-pop. */
    fun renameFolder(newName: String) {
        moveController.rename(viewModelScope, bucketName.value, newName) { renamed ->
            bucketName.value = renamed
            _folderRenameConfirmation.tryEmit(renamed)
        }
    }

    /**
     * Return every selected vaulted photo to the device.
     *
     * [HiddenVaultRestorer] owns the round trip, so a photo revealed from inside its folder comes back
     * with the same name, place and cloud pairing it would from the vault screen, and the folder stops
     * being hidden once the vault holds nothing more of it.
     */
    fun unhideSelected() {
        val uris = selection.value.filter { it in _vaultedUris.value }
        if (uris.isEmpty()) return
        selection.clear()
        viewModelScope.launch {
            val failed = hiddenVaultRestorer.restoreAll(uris)
            if (failed > 0) {
                _hideFailure.tryEmit(
                    context.resources.getQuantityString(R.plurals.hidden_restore_failed_some, failed, failed),
                )
            }
        }
    }

    /**
     * Delete selected device photos: [freeUpSpace] removes on-device, [deleteFromCloud] also trashes the
     * Drive copy. On Android 11+ the local delete routes through the system trash dialog and defers the cloud delete.
     */
    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            // A local (device) delete needs no account; the use case only requires a signed-in user
            // for a cloud trash, which local-only mode never produces. Pass the nullable userId through.
            val userId = primaryUserId ?: accountManager.getPrimaryUserId().first()
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
     * timeline uses ([GalleryViewModel.hideSelected]). A photo with a device file, backed up or not, is
     * copied into app-private storage and routed through [DeletePhotoUseCase] with `freeUpSpace=true,
     * deleteFromCloud=false, hide=true` so the MediaStore original is removed (one system-delete dialog
     * on Android 11+). A cloud-only photo has no device file and hides by linkId; the Drive copy is
     * never touched either way.
     *
     * The intent is journalled BEFORE that delete and confirmed after it, so an interruption between
     * the two leaves a repairable record instead of bytes nothing refers to — see [HiddenVaultJournal].
     * A selection is short enough to watch behind the blocking sheet, so it reports no progress of
     * its own; [toggleHiddenCard] runs the same body with progress for a whole folder.
     */
    fun hideSelected() {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                runHide(HiddenFolderRecords.hideSplit(items), reportProgress = false)
            } finally {
                _isDeleting.value = false
            }
        }
    }

    /**
     * The two halves the current selection's hide would act on, for the confirmation that fronts it.
     *
     * The very split [hideSelected] runs on, read from the same selection, so the sheet describes
     * exactly what the Hide button is about to do rather than what hiding does in general.
     */
    fun hideSplitForSelection(): HiddenFolderRecords.HideSplit =
        HiddenFolderRecords.hideSplit(selectedGalleryItems())

    /**
     * The same two halves for a hide of the whole folder, for the confirmation the folder drawer's
     * Hide row raises.
     *
     * Read off exactly what [toggleHiddenCard] routes: this screen's own photos. A folder opened
     * from the vault side is being unhidden rather than hidden, so the drawer asks this only on the
     * device side.
     */
    fun folderHideSplitPreview(): HiddenFolderRecords.HideSplit = HiddenFolderRecords.folderHideSplit(
        items = _items.value,
        bucketName = bucketName.value,
    )

    /**
     * The one hide body every entry point on this screen runs, whether it was handed a selection or
     * the whole folder.
     *
     * [split] carries both halves of the hide, decided by [HiddenFolderRecords] so no caller routes
     * a photo its own way: every photo with a device file moves into the vault, and the cloud-only
     * ones, which have no file to move, hide by their cloud linkId. Acting on both is what makes the
     * hide cover everything the user asked for.
     *
     * [reportProgress] publishes done/total into [folderVault] and polls the stop flag between
     * copies, which is what makes a folder of thousands watchable and stoppable. A stop is honoured
     * only during the copies: what has already been copied still goes through the journal and the
     * delete, so those photos end up in the vault rather than copied for nothing, and everything
     * after the stop is left untouched on the device.
     */
    private suspend fun runHide(split: HiddenFolderRecords.HideSplit, reportProgress: Boolean) {
        val vaultable = split.vaultable
        HiddenVaultDiagnostics.hideStarted(split)
        // Client-side hide for the cloud-only members: add their linkIds to the hidden set so the
        // shared merge filter drops them everywhere. They have no device file at all and nothing on
        // Drive changes, so unhide re-includes them with no re-pairing.
        HiddenCloudPhotos.hide(context, split.cloudLinkIds)
        pendingHideCloudLinkIds = split.cloudLinkIds
        pendingHideFailures = 0
        if (vaultable.isEmpty()) {
            // Nothing to copy, so the client-side hide above is the whole operation and it is
            // already done: finish cleanly rather than raising a count that would never move. The
            // Undo bar is raised for it exactly as it is for a vaulting hide, so the same button
            // stays reversible whichever kind of photo it was pressed on.
            // The cloud-only half is the whole hide here and it landed, so the folder stays
            // hidden and its pending name is released rather than rolled back. Left set, it
            // outlives this hide on a long-lived screen and the next rollback for anything
            // else reads it as its own, revealing a folder the user never asked to reveal.
            pendingHideCloudLinkIds = emptyList()
            pendingHideFolderName = null
            buildHideUndoAction(emptyList(), split.cloudLinkIds)?.let { undoController.offer(it) }
            selection.clear()
            return
        }
        // Step 1: refuse up front when the copies cannot fit, measured over the WHOLE batch. A hide
        // holds both the originals and the vault copies at once, so a volume that runs out mid-batch
        // fails per file with nothing the user can act on — and for a folder it would already have
        // deleted the originals of everything copied before that point.
        val shortfall = hiddenVaultJournal.spaceShortfallBytes(vaultable.sumOf { it.sizeBytes })
        if (shortfall > 0L) {
            rollbackPendingHide()
            _hideFailure.tryEmit(
                context.getString(R.string.gallery_hide_needs_free_space, formatBytes(shortfall)),
            )
            return
        }
        // Step 2: copy each device file into app-private hidden storage. A backed-up photo also
        // stashes its cloud linkId so the reveal re-pairs by id instead of re-uploading.
        val collected = mutableListOf<HiddenVaultJournal.Entry>()
        var hideFailures = 0
        if (reportProgress) _folderVault.value = HiddenFolderProgress(0, vaultable.size, restoring = false)
        for (target in vaultable) {
            if (reportProgress && stopFolderVault.get()) break
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
                // store() already logged the reason (privacy-safe, no file name). The photo stays
                // visible, so it is counted rather than dropped: a hide that reported plain success
                // would be describing a state the user can see is not true.
                hideFailures++
            }
            if (reportProgress) {
                _folderVault.value = HiddenFolderProgress(collected.size, vaultable.size, restoring = false)
            }
        }
        HiddenVaultDiagnostics.copied(collected.size, hideFailures)
        if (collected.isEmpty()) {
            rollbackPendingHide()
            _hideFailure.tryEmit(context.getString(R.string.gallery_copy_to_hidden_failed))
            return
        }
        // Step 3: record the intent BEFORE anything is deleted, so an interruption during the
        // delete leaves a recoverable state rather than orphaned bytes.
        if (!hiddenVaultJournal.journal(collected)) {
            hiddenVaultJournal.discard(collected.map { it.privateUri })
            rollbackPendingHide()
            _hideFailure.tryEmit(context.getString(R.string.gallery_move_to_hidden_failed))
            return
        }
        pendingHidePrivateUris = collected.map { it.privateUri }
        pendingHideFailures = hideFailures

        // Step 4: delete the MediaStore originals of exactly what was copied (one system-delete
        // dialog on Android 11+). A stopped copy pass narrows this to its own prefix, which is what
        // leaves the photos it never reached where the user can still see them.
        val deleting = HiddenVaultDecisions.deletableOriginals(vaultable, collected)
        // Hiding a device photo needs no account; the vault is app-private and the use case only
        // requires a signed-in user for a cloud trash, which a hide never performs (deleteFromCloud =
        // false). Pass the nullable userId through instead of aborting a guest hide here.
        val userId = accountManager.getPrimaryUserId().first()
        when (val result = deletePhotoUseCase(userId, deleting, freeUpSpace = true, deleteFromCloud = false, hide = true)) {
            is DeletePhotoUseCase.Result.Success -> {
                HiddenVaultDiagnostics.originalsRemoved(deleting.size, neededConsent = false)
                // Snapshot both halves before commitPendingHide() clears the pending list, so Undo
                // reverses exactly the hide that just landed.
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
    }

    /** Publish the journalled hide into HIDDEN_PHOTO_URIS now that the delete has confirmed, so it
     *  survives a restart and the load() filter keeps it out of the folder. */
    private suspend fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        // The hide landed, so the folder keeps the flag this run wrote.
        pendingHideFolderName = null
        hiddenVaultJournal.confirm(uris)
    }

    /** The client-side half of an in-flight hide, held so the Undo offered once the delete confirms
     *  reverses the whole hide rather than only the photos that were vaulted. */
    private var pendingHideCloudLinkIds: List<String> = emptyList()

    /** How many device files that hide could not copy into the vault. Carried to whichever commit
     *  path lands so the count is reported once the hide is actually done. */
    private var pendingHideFailures = 0

    /** Say how many photos a landed hide left behind, and only then: a photo whose copy failed is
     *  still on the device, so a hide that reported plain success would contradict the grid. */
    private fun reportHideFailures() {
        val failures = pendingHideFailures
        pendingHideFailures = 0
        if (failures <= 0) return
        _hideFailure.tryEmit(
            context.resources.getQuantityString(R.plurals.gallery_hide_partial_failed, failures, failures),
        )
    }

    /** Undo a hide that did not land (error or cancelled dialog): discard the private copies and
     *  everything journalled for them, and put the cloud-only half back in every listing. Those ids
     *  are written before the device half is even attempted, so leaving them set is what made a
     *  refused hide still take photos away. The originals are untouched. */
    /** The folder a running hide flagged as hidden, cleared once that hide has landed. Null when no
     *  folder hide is in flight, which is every selection hide. */
    private var pendingHideFolderName: String? = null

    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        val cloudIds = pendingHideCloudLinkIds
        val folderName = pendingHideFolderName
        pendingHidePrivateUris = emptyList()
        pendingHideCloudLinkIds = emptyList()
        pendingHideFolderName = null
        pendingHideFailures = 0
        if (uris.isEmpty() && cloudIds.isEmpty() && folderName == null) return
        viewModelScope.launch {
            if (uris.isNotEmpty()) hiddenVaultJournal.discard(uris)
            HiddenCloudPhotos.reveal(context, cloudIds)
            // Nothing was vaulted, so a folder left flagged would list in the vault holding no
            // photos while its card is gone from Albums, with no way back except revealing it.
            if (folderName != null) setFolderFlag(SettingsKeys.HIDDEN_FOLDER_NAMES, enable = false)
        }
    }

    /** Run the deferred cloud delete once the system trash dialog is confirmed, then clear. A hide
     *  flow commits its vault copies here once the local delete is confirmed. */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        _pendingDeleteIntent.value = null
        viewModelScope.launch {
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
                        _hideFailure.tryEmit(context.getString(R.string.viewer_delete_drive_failed))
                    }
                    if (pending.hide) {
                        // Snapshot both halves before commitPendingHide() clears them, then offer Undo.
                        val hideUris = pendingHidePrivateUris
                        val hideCloudIds = pendingHideCloudLinkIds
                        pendingHideCloudLinkIds = emptyList()
                        commitPendingHide()
                        buildHideUndoAction(hideUris, hideCloudIds)?.let { undoController.offer(it) }
                        reportHideFailures()
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

    /** User cancelled the system trash dialog — drop the deferred cloud work and any pending vault
     *  copies. The originals are untouched, so the photos stay where the user already sees them. */
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
    private var pendingStripFailed = 0

    fun stripMetadataSelected(config: MetadataStripConfig) {
        val uris = selectedDeviceUris()
        if (uris.isEmpty() || config.isNoOp) return
        viewModelScope.launch {
            _multiStripState.value = MultiStripState.Working
            runStripPass(config, uris, baseStripped = 0, baseSkipped = 0, baseFailed = 0)
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
                context.contentResolver, needsPermission.map(android.net.Uri::parse),
            )
            _multiStripState.value = MultiStripState.Idle
            return
        }
        selection.clear()
        _multiStripState.value = terminalStripState(
            totalStripped, totalSkipped + needsPermission.size, totalFailed,
        )
    }

    fun onStripPermissionGranted() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        val baseFailed = pendingStripFailed
        clearPendingStripState()
        _pendingStripIntent.value = null
        viewModelScope.launch {
            _multiStripState.value = MultiStripState.Working
            runStripPass(config, uris, baseStripped, baseSkipped, baseFailed)
        }
    }

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
}
