package me.proton.photos.presentation.albums

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import me.proton.photos.util.ProtonPhotosStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.LocalAlbum
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.repository.SyncStateRepository
import java.io.File
import javax.inject.Inject

/**
 * Result of a virtual-only local-album rename or delete. Pure-virtual albums never need a
 * system consent dialog because we only mutate DataStore (no file movement).
 *
 * Bucket-derived "albums" (Camera, Screenshots, …) are rejected at the call site with
 * [Failed] — the user is told to use the system file manager.
 */
sealed interface LocalAlbumActionResult {
    data object Done : LocalAlbumActionResult
    data class Failed(val message: String) : LocalAlbumActionResult
    /**
     * Virtual-only delete completed locally. The album also had a matching cloud counterpart
     * (same name) — the UI should ask the user whether to delete it too, then call
     * [AlbumsViewModel.confirmCloudDeleteForLocalAlbum] / [cancelCloudDeleteForLocalAlbum].
     */
    data class DoneWithCloudPending(
        val albumName: String,
        val cloudLinkId: String,
    ) : LocalAlbumActionResult
}

enum class AlbumsFilter { All, Local, BackedUp }

data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val localAlbums: List<LocalAlbum> = emptyList(),
    val albumsFilter: AlbumsFilter = AlbumsFilter.All,
    val error: String? = null,
    val isCreatingAlbum: Boolean = false,
    val createAlbumError: String? = null,
) {
    val visibleCloudAlbums: List<Album> get() = when (albumsFilter) {
        AlbumsFilter.All, AlbumsFilter.BackedUp -> albums
        AlbumsFilter.Local -> emptyList()
    }
    val visibleLocalAlbums: List<LocalAlbum> get() = when (albumsFilter) {
        AlbumsFilter.All, AlbumsFilter.Local -> localAlbums
        AlbumsFilter.BackedUp -> emptyList()
    }
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val localMediaRepo: LocalMediaRepository,
    private val syncStateRepo: SyncStateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        loadAlbums()
        observeLocalAlbums()
    }

    fun refresh() = loadAlbums()

    private fun observeLocalAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            val syncFlow = if (userId != null) syncStateRepo.observeAll(userId) else flowOf(emptyList())
            val hiddenUrisFlow = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet() }
            val manualAlbumsFlow = context.settingsDataStore.data
                .map { it[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet() }
            // Virtual-membership map ("albumName||uri" entries). See SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP
            // for why we use this instead of physically moving files. Parsed into
            // Map<albumName, Set<contentUri>> so the merge below is O(1) per bucket file.
            val virtualMembershipFlow = context.settingsDataStore.data
                .map { prefs ->
                    val raw = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
                    raw.mapNotNull { entry ->
                        val sep = entry.indexOf("||")
                        if (sep <= 0 || sep == entry.length - 2) null
                        else entry.substring(0, sep) to entry.substring(sep + 2)
                    }.groupBy({ it.first }, { it.second })
                     .mapValues { it.value.toSet() }
                }
            combine(
                localMediaRepo.observeLocalMedia(),
                syncFlow,
                hiddenUrisFlow,
                manualAlbumsFlow,
                virtualMembershipFlow,
            ) { items, syncStates, hiddenUris, manualNames, virtualMembership ->
                val syncedUris = syncStates
                    .filter { it.status == SyncStatus.SYNCED }
                    .map { it.localUri }
                    .toSet()
                val visibleItems = items.filter { it.uri !in hiddenUris }
                // Auto-discovered buckets that actually contain photos. A bucket-derived album
                // is NOT virtual-only — even if it happens to be in MANUAL_LOCAL_ALBUMS, the
                // presence of real MediaStore files for that bucket name means we must refuse
                // rename/delete (device-folder territory).
                val populated = visibleItems
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        // Real bucket items + any virtual-membership entries that resolve to a
                        // known local URI. dedup by URI in case a user adds a Camera photo to
                        // its own bucket name (no-op but harmless).
                        val virtualUris = virtualMembership[name].orEmpty()
                        val virtualItems = visibleItems.filter { it.uri in virtualUris }
                        val merged = (groupItems + virtualItems).distinctBy { it.uri }
                        val sorted = merged.sortedByDescending { it.dateTaken }
                        LocalAlbum(
                            name          = name,
                            coverUri      = sorted.firstOrNull()?.uri,
                            itemCount     = sorted.size,
                            items         = sorted,
                            backedUpCount = sorted.count { it.uri in syncedUris },
                            isManual      = name in manualNames,
                            isVirtualOnly = false,
                        )
                    }
                // Manual albums the user created but which are still empty (no photos in that
                // bucket yet) — surface them so the user can add to / delete them. A manual
                // album may also have only virtual entries (no real bucket on disk); in that
                // case promote it from "empty" to a populated virtual-only album.
                val populatedNames = populated.map { it.name }.toSet()
                val virtualOnly = manualNames
                    .filter { it.isNotBlank() && it !in populatedNames }
                    .map { name ->
                        val virtualUris = virtualMembership[name].orEmpty()
                        val virtualItems = visibleItems
                            .filter { it.uri in virtualUris }
                            .sortedByDescending { it.dateTaken }
                        LocalAlbum(
                            name          = name,
                            coverUri      = virtualItems.firstOrNull()?.uri,
                            itemCount     = virtualItems.size,
                            items         = virtualItems,
                            backedUpCount = virtualItems.count { it.uri in syncedUris },
                            isManual      = true,
                            isVirtualOnly = true,
                        )
                    }
                (populated + virtualOnly).sortedByDescending { it.items.firstOrNull()?.dateTaken ?: 0L }
            }.collectLatest { localAlbums ->
                _uiState.update { it.copy(localAlbums = localAlbums) }
            }
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val albums = driveRepo.loadAlbums(userId)
                _uiState.update { it.copy(isLoading = false, albums = albums) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Failed to load albums: ${e.message}") }
            }
        }
    }

    fun setFilter(filter: AlbumsFilter) {
        _uiState.update { it.copy(albumsFilter = filter) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun createAlbum(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isCreatingAlbum = true, createAlbumError = null) }
            try {
                val album = driveRepo.createDriveAlbum(userId, trimmed)
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    albums = (listOf(album) + it.albums),
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    createAlbumError = e.message ?: "Failed to create album",
                ) }
            }
        }
    }

    fun clearCreateAlbumError() = _uiState.update { it.copy(createAlbumError = null) }

    /**
     * Creates a manual local album: registers the name in DataStore so it shows up in the
     * Albums grid even when empty, and best-effort creates a matching `Pictures/<name>` folder
     * so MediaStore can pick it up the next time the user moves a file there.
     */
    fun createLocalAlbum(name: String) {
        val trimmed = ProtonPhotosStorage.sanitize(name)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // Persist the album name first — even when the disk mkdir fails (scoped storage),
            // the DataStore entry keeps the album visible in the Albums grid.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
                prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = current + trimmed
            }
            // Best-effort directory creation under Pictures/Proton Photos/<name>. On Q+ scoped
            // storage the app may not have permission for the public Pictures directory, but
            // the first MediaStore insert with RELATIVE_PATH=Pictures/Proton Photos/<name>
            // creates the folder for us anyway. Silent failure here is OK.
            runCatching {
                val picsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                File(picsRoot, "${ProtonPhotosStorage.ROOT_NAME}/$trimmed").mkdirs()
            }
        }
    }

    /**
     * Removes a manual-album marker from DataStore — the user-facing entry for empty manual
     * albums (no photos in the bucket yet). Auto-discovered buckets are unaffected by this.
     * Called as a follow-up after [deleteLocalAlbum] succeeds, since the bucket can carry both
     * a marker and physical files.
     */
    private suspend fun removeManualAlbumMarker(name: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
            prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = current - name
            // Also drop any virtual-membership entries pointing at this album so a freshly
            // created album with the same name doesn't inherit stale URIs.
            val virtual = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
            val kept = virtual.filterNot { it.startsWith("$name||") }.toSet()
            if (kept.size != virtual.size) {
                prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] = kept
            }
        }
    }

    // ── Pure-virtual local-album rename + delete ─────────────────────────────
    //
    // Album operations no longer move files on disk. The bucket-derived "albums" (Camera,
    // Screenshots, …) are device folders we won't touch — the user is told to use the
    // system file manager if they want to delete them. The user-created (virtual-only)
    // albums live entirely in DataStore: rename/delete are O(1) preferences edits.
    //
    // Cloud-side mirror lives in [maybeRenameCloudAlbumByName] /
    // [findCloudAlbumLinkIdByName] / [confirmCloudDeleteForLocalAlbum].

    /**
     * Returns true when [name] has NO real MediaStore bucket rows — only virtual-membership
     * entries (i.e. user added cloud-only or third-party photos to a user-created album).
     * Bucket-derived albums (e.g. Camera) return false and must NOT be renamed/deleted by us.
     */
    private fun isAlbumVirtualOnly(name: String): Boolean {
        val selection = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
        val args = arrayOf(name)
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection, args, "${MediaStore.MediaColumns._ID} LIMIT 1",
                )?.use { c ->
                    if (c.moveToFirst()) return false
                }
            } catch (_: Exception) {
                // If a collection is inaccessible, fall through — we'll treat it as "no rows
                // here" and continue. Worst case is a virtual-only check that misses some
                // collection and proceeds to rename/delete a marker that no longer matters.
            }
        }
        return true
    }

    /**
     * Renames a virtual-only album. The bucket-derived guard runs first: real device folders
     * are rejected with a Failed message. No file movement, no system consent dialog.
     *
     * Also mirrors the rename to the matching cloud album (best-effort, silent on failure).
     */
    fun renameLocalAlbum(currentName: String, newName: String): Flow<LocalAlbumActionResult> = flow {
        val sanitized = ProtonPhotosStorage.sanitize(newName)
        if (sanitized.isEmpty()) {
            emit(LocalAlbumActionResult.Failed("Album name cannot be empty"))
            return@flow
        }
        if (sanitized == currentName) {
            emit(LocalAlbumActionResult.Failed("New name is the same as the current name"))
            return@flow
        }
        // Collision check — refuse if a different bucket with the new name already exists,
        // so we don't silently merge the user's albums.
        val existingNames = _uiState.value.localAlbums.map { it.name.lowercase() }.toSet()
        if (sanitized.lowercase() in existingNames) {
            emit(LocalAlbumActionResult.Failed("An album named \"$sanitized\" already exists"))
            return@flow
        }
        if (!isAlbumVirtualOnly(currentName)) {
            emit(LocalAlbumActionResult.Failed(
                "\"$currentName\" is a device folder. Use the system file manager to rename it.",
            ))
            return@flow
        }
        val outcome = applyRename(currentName, sanitized)
        emit(outcome)
        // Cloud mirror — best-effort, doesn't gate the local result.
        viewModelScope.launch { maybeRenameCloudAlbumByName(currentName, sanitized) }
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes a virtual-only album. Bucket-derived albums are rejected. If a same-name cloud
     * album exists, the flow emits [DoneWithCloudPending] so the UI can ask the user whether
     * to delete the cloud copy too.
     */
    fun deleteLocalAlbum(name: String): Flow<LocalAlbumActionResult> = flow {
        if (!isAlbumVirtualOnly(name)) {
            emit(LocalAlbumActionResult.Failed(
                "\"$name\" is a device folder. Use the system file manager to delete it.",
            ))
            return@flow
        }
        val outcome = applyDelete(name)
        if (outcome is LocalAlbumActionResult.Done) {
            // Look for a matching cloud album so the UI can offer to delete it too.
            val cloudLinkId = findCloudAlbumLinkIdByName(name)
            if (cloudLinkId != null) {
                emit(LocalAlbumActionResult.DoneWithCloudPending(name, cloudLinkId))
                return@flow
            }
        }
        emit(outcome)
    }.flowOn(Dispatchers.IO)

    /** Pure DataStore rename. */
    private suspend fun applyRename(
        oldName: String,
        newName: String,
    ): LocalAlbumActionResult {
        context.settingsDataStore.edit { prefs ->
            // Rename the manual-album marker if present.
            val markers = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
            if (oldName in markers) {
                prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = (markers - oldName) + newName
            }
            // Migrate every "oldName||uri" → "newName||uri".
            val virtual = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
            val (matching, rest) = virtual.partition { it.startsWith("$oldName||") }
            if (matching.isNotEmpty()) {
                prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] =
                    (rest + matching.map { "$newName||${it.substringAfter("||")}" }).toSet()
            }
        }
        return LocalAlbumActionResult.Done
    }

    /**
     * Pure DataStore delete. Drops the manual-album marker and every virtual-membership entry
     * for [name].
     */
    private suspend fun applyDelete(name: String): LocalAlbumActionResult {
        context.settingsDataStore.edit { prefs ->
            val markers = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
            prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = markers - name
            val virtual = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
            prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] =
                virtual.filterNot { it.startsWith("$name||") }.toSet()
        }
        return LocalAlbumActionResult.Done
    }

    /**
     * Best-effort: look up the cloud album with the same name (case-insensitive) and rename
     * it on Drive. Silent on every failure — local rename already succeeded by the time we
     * reach here, and the next loadAlbums() pass will reflect whatever state the server has.
     *
     * Also re-keys the bucket→linkId cache in [SettingsKeys.ALBUM_BUCKET_MAP] so the next
     * SyncWorker run finds the renamed album by its new name. Without this, the cache would
     * still point at oldName=linkId and a subsequent upload from a renamed bucket would
     * silently create a brand-new cloud album with the new name — leaving the original
     * orphaned. Atomic with the manual-album marker swap.
     */
    private suspend fun maybeRenameCloudAlbumByName(oldName: String, newName: String) {
        try {
            val userId = accountManager.getPrimaryUserId().first() ?: return
            val linkId = findCloudAlbumLinkIdByName(oldName) ?: return
            driveRepo.renameAlbum(userId, linkId, newName)
            // Refresh in-memory album list so the new name appears without a full reload.
            _uiState.update { state ->
                state.copy(albums = state.albums.map {
                    if (it.linkId == linkId) it.copy(name = newName) else it
                })
            }
            // Re-key the bucket→linkId cache so the SyncWorker doesn't drift. Parse each
            // "bucket=linkId" entry, swap the bucket name when (and only when) it matches
            // oldName AND the stored linkId matches the renamed album. Doing both checks
            // avoids accidentally hijacking a separate bucket that happens to share oldName.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()
                val rebuilt = current.mapNotNull { entry ->
                    val idx = entry.indexOf('=')
                    if (idx <= 0) return@mapNotNull entry
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    if (bucket.equals(oldName, ignoreCase = true) && storedLinkId == linkId) {
                        "$newName=$linkId"
                    } else entry
                }.toSet()
                if (rebuilt != current) {
                    prefs[SettingsKeys.ALBUM_BUCKET_MAP] = rebuilt
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AlbumsVM", "Cloud rename mirror failed: ${e.message}")
        }
    }

    /** Case-insensitive lookup by name against the currently-loaded cloud album list. */
    private fun findCloudAlbumLinkIdByName(name: String): String? =
        _uiState.value.albums.firstOrNull { it.name.equals(name, ignoreCase = true) }?.linkId

    /**
     * Called from the UI after the user confirms the "also delete on cloud" prompt that the
     * [LocalAlbumActionResult.DoneWithCloudPending] outcome triggered.
     */
    fun confirmCloudDeleteForLocalAlbum(cloudLinkId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            try {
                driveRepo.deleteAlbum(userId, cloudLinkId)
                _uiState.update { state ->
                    state.copy(albums = state.albums.filter { it.linkId != cloudLinkId })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Cloud album delete failed: ${e.message}") }
            }
        }
    }

    /** Called when the user declines the "also delete on cloud" prompt — no-op. */
    fun cancelCloudDeleteForLocalAlbum() {
        // Local delete already happened. Nothing more to do.
    }

    fun deleteAlbum(albumLinkId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            try {
                driveRepo.deleteAlbum(userId, albumLinkId)
                _uiState.update { state ->
                    state.copy(albums = state.albums.filter { it.linkId != albumLinkId })
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to delete album: ${e.message}") }
            }
        }
    }
}
