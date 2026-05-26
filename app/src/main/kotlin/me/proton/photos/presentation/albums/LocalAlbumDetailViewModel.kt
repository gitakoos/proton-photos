package me.proton.photos.presentation.albums

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
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.usecase.GetGalleryItemsUseCase
import javax.inject.Inject

@HiltViewModel
class LocalAlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
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
     * [GalleryItem.CloudOnly] items are excluded because they have no on-device counterpart
     * and don't belong to a local album view.
     */
    fun loadAlbum(bucketName: String) {
        currentAlbumName = bucketName
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
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
            combine(getGalleryItems.invoke(userId), virtualUrisFlow) { all, virtualUris ->
                all.filter { item ->
                    when (item) {
                        is GalleryItem.LocalOnly ->
                            item.local.bucketName == bucketName || item.local.uri in virtualUris
                        is GalleryItem.Synced ->
                            item.local.bucketName == bucketName || item.local.uri in virtualUris
                        is GalleryItem.CloudOnly -> false
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
}

