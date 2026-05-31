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

package eu.akoos.photos.presentation.albums

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import javax.inject.Inject

@HiltViewModel
class LocalAlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items.asStateFlow()

    /**
     * URIs of items the user has long-pressed to select. Selection-mode toggles whenever this
     * is non-empty (mirrors [AlbumDetailViewModel.isSelectionMode]). Drives the remove-from-album
     * toolbar button — selection is uri-keyed so it survives item-list reorders.
     */
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    /**
     * One-shot human-readable message emitted after [removeSelectedFromAlbum] completes. The
     * screen collects this in a [androidx.compose.runtime.LaunchedEffect] and shows a snackbar.
     * Replay=0 so a re-subscription doesn't show stale messages.
     */
    private val _removeMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val removeMessage: SharedFlow<String> = _removeMessage.asSharedFlow()

    /**
     * The bucket name we're currently observing. Persisted so [removeSelectedFromAlbum] knows
     * which album-name prefix to strip from [SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP].
     */
    @Volatile private var currentAlbumName: String = ""

    /**
     * Start observing gallery items for the album named [bucketName].
     *
     * Includes [GalleryItem.LocalOnly] and [GalleryItem.Synced] items that either:
     *   - live in the matching MediaStore bucket (auto-discovered membership), OR
     *   - were added via "Add to album" and recorded in
     *     [SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] (virtual membership — file is still
     *     in its real Camera bucket, but it's also referenced by this album).
     *
     * When [cloudAlbumLinkId] is non-null, this is a MERGED album — a local bucket whose
     * name matches an existing Drive album. In that case the grid also includes
     * [GalleryItem.CloudOnly] photos that belong to the cloud album, so the user sees the
     * union of "what's on this device in this bucket" and "what's in the matching Drive
     * album". Dedup happens automatically: a backed-up photo surfaces as a Synced item, not
     * as both LocalOnly + CloudOnly.
     */
    private val _isRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRefreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRefreshing

    private var currentCloudAlbumLinkId: String? = null

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val userId = accountManager.getPrimaryUserId().first()
                if (userId != null) {
                    runCatching { driveRepo.refreshCloudPhotosIncremental(userId) }
                }
                if (currentAlbumName.isBlank()) return@launch
                loadAlbum(currentAlbumName, currentCloudAlbumLinkId)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadAlbum(bucketName: String, cloudAlbumLinkId: String? = null) {
        currentCloudAlbumLinkId = cloudAlbumLinkId
        currentAlbumName = bucketName
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch

            // Resolve cloud album membership once on load — `loadAlbumPhotos` already serves
            // the DB cache for repeat calls, so this is fast enough to keep inline. Failures
            // degrade gracefully to "only show local items" instead of blowing up the screen.
            val cloudAlbumLinkIds: Set<String> = if (cloudAlbumLinkId == null) emptySet()
            else runCatching {
                driveRepo.loadAlbumPhotos(userId, cloudAlbumLinkId, volumeId = null)
                    .map { it.linkId }
                    .toSet()
            }.getOrElse { emptySet() }

            val virtualUrisFlow = context.settingsDataStore.data
                .map { prefs ->
                    val raw = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
                    raw.mapNotNull { entry ->
                        val sep = entry.indexOf("||")
                        if (sep <= 0 || sep == entry.length - 2) null
                        else if (entry.substring(0, sep) == bucketName) entry.substring(sep + 2)
                        else null
                    }.toSet()
                }
            // Hidden vault filter — same DataStore key as gallery + albums grid.
            // Excludes hidden URIs from this bucket view.
            val hiddenUrisFlow = context.settingsDataStore.data.map {
                it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            }
            combine(
                getGalleryItems.invoke(userId),
                virtualUrisFlow,
                hiddenUrisFlow,
            ) { all, virtualUris, hiddenUris ->
                all.filter { item ->
                    val uri = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        is GalleryItem.CloudOnly -> null
                    }
                    if (uri != null && uri in hiddenUris) return@filter false
                    when (item) {
                        is GalleryItem.LocalOnly ->
                            item.local.bucketName == bucketName || item.local.uri in virtualUris
                        is GalleryItem.Synced ->
                            item.local.bucketName == bucketName ||
                                item.local.uri in virtualUris ||
                                item.cloud.linkId in cloudAlbumLinkIds
                        is GalleryItem.CloudOnly ->
                            item.cloud.linkId in cloudAlbumLinkIds
                    }
                }
            }.collect { _items.value = it }
        }
    }

    fun toggleSelection(uri: String) {
        _selectedUris.update {
            if (uri in it) it - uri else it + uri
        }
    }

    fun clearSelection() {
        _selectedUris.value = emptySet()
    }

    /**
     * Drops every selected URI from [SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] for the
     * currently observed album. Bucket-derived items (where the underlying MediaStore bucketName
     * matches the album name) are refused — those are real folder members, not virtual entries,
     * and would reappear on the next observation pass anyway. Surfaces a snackbar message via
     * [removeMessage] summarizing how many were removed and how many were skipped.
     *
     * No-op on empty selection so the toolbar button is safe to mash.
     */
    fun removeSelectedFromAlbum() {
        val albumName = currentAlbumName
        if (albumName.isEmpty()) return
        val selected = _selectedUris.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            // Partition by "is this a bucket member" up front so we can give the user an
            // accurate snackbar message before mutating DataStore.
            val (bucketMembers, virtualMembers) = selected.partition { uri ->
                val item = _items.value.firstOrNull { gi ->
                    when (gi) {
                        is GalleryItem.LocalOnly -> gi.local.uri == uri
                        is GalleryItem.Synced -> gi.local.uri == uri
                        is GalleryItem.CloudOnly -> false
                    }
                }
                val bucketName = when (item) {
                    is GalleryItem.LocalOnly -> item.local.bucketName
                    is GalleryItem.Synced -> item.local.bucketName
                    else -> null
                }
                bucketName == albumName
            }

            if (virtualMembers.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
                    val toDrop = virtualMembers.map { uri -> "$albumName||$uri" }.toSet()
                    val updated = current - toDrop
                    if (updated.size != current.size) {
                        prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] = updated
                    }
                }
            }

            // Clear selection regardless — the user's intent was "I'm done with these".
            _selectedUris.value = emptySet()

            // Compose a single-sentence snackbar message. Pluralization is plain text here
            // (no resource file plurals) because we're already in mixed-message territory.
            val parts = buildList {
                if (virtualMembers.isNotEmpty()) {
                    add(
                        "Removed ${virtualMembers.size} photo" +
                            if (virtualMembers.size != 1) "s" else "",
                    )
                }
                if (bucketMembers.isNotEmpty()) {
                    add(
                        "${bucketMembers.size} bucket folder member" +
                            (if (bucketMembers.size != 1) "s" else "") +
                            " can't be removed — move the file with your file manager",
                    )
                }
            }
            if (parts.isNotEmpty()) {
                _removeMessage.tryEmit(parts.joinToString(". "))
            }
        }
    }

    /** Cached primary userId for synchronous PhotoCell-side dispatch. Same rationale as
     *  GalleryViewModel.primaryUserId — the cell can't suspend during recomposition. */
    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    init {
        viewModelScope.launch { accountManager.getPrimaryUserId().collect { primaryUserId = it } }
    }

    /**
     * Enqueue an on-demand thumbnail decrypt for a CloudOnly cell that's just entered the
     * merged-album viewport. No-op until the first primary-user emission lands.
     */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /** Cancel any in-flight decrypt for [linkId] when the cell scrolls off-screen. */
    fun cancelThumbnailDecrypt(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
    }
}

