package eu.akoos.photos.presentation.hidden

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

data class HiddenAlbumUiState(
    val isAuthenticated: Boolean = false,
    val items: List<LocalMediaItem> = emptyList(),
    /** hiddenUri → has-cloud-counterpart. Derived from HIDDEN_URI_CLOUD_ID_MAP at load
     *  time so the cell can render a green cloud badge for hidden photos that are also
     *  backed up — without this the user couldn't tell which hidden items are safe to
     *  delete (cloud copy exists) vs which are device-only. */
    val backedUpUris: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: SyncStateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HiddenAlbumUiState())
    val uiState: StateFlow<HiddenAlbumUiState> = _uiState.asStateFlow()

    fun onAuthenticationSuccess() {
        _uiState.value = _uiState.value.copy(isAuthenticated = true)
        loadHiddenPhotos()
    }

    fun onAuthenticationFailed() {
        _uiState.value = _uiState.value.copy(isAuthenticated = false)
    }

    private fun loadHiddenPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val prefs = context.settingsDataStore.data.first()
                val hiddenUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                // Pull the hiddenUri → cloudLinkId tokens we stashed at hide time. The
                // keys of that map = the hidden items that are also backed up to Drive.
                val backedUpUris = (prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet())
                    .map { it.substringBefore('|') }
                    .toSet()
                val items = hiddenUris.mapNotNull { uri ->
                    localMediaRepo.queryByUri(uri)
                }
                _uiState.value = _uiState.value.copy(
                    items = items,
                    backedUpUris = backedUpUris,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun hidePhoto(uri: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uri
            }
            // Reload
            loadHiddenPhotos()
        }
    }

    fun unhidePhoto(uri: String) {
        viewModelScope.launch {
            // Look up the cloud linkId we stashed at hide time so we can transplant the
            // existing SyncState row onto the freshly-restored MediaStore URI — that's
            // what keeps reconcile from treating the restored file as a brand-new local
            // photo and re-uploading it as a duplicate Drive entry.
            val cloudLinkId: String? = run {
                val prefs = context.settingsDataStore.data.first()
                val tokens = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                tokens.firstOrNull { it.startsWith("$uri|") }?.substringAfter('|')
            }
            // If [uri] is an app-private hidden file, restore it to MediaStore so other gallery
            // apps can see it again, then drop it from the hidden set.
            var restoredUri: String? = null
            if (hiddenStorage.isHiddenUri(uri)) {
                val displayName = _uiState.value.items.firstOrNull { it.uri == uri }?.displayName
                restoredUri = withContext(Dispatchers.IO) { hiddenStorage.restore(uri, displayName) }
            }
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - uri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = mapping.filterNot { it.startsWith("$uri|") }.toSet()
            }
            // Synced-photo round-trip: pull the OLD HIDDEN SyncState row (keyed on the
            // pre-hide URI) forward onto the new MediaStore URI, status=SYNCED. Without
            // this, the restored file has no SyncState — reconcile sees existingSync=null,
            // can't match by id/hash, falls through to byName/byNameAndDate, and on any
            // mismatch (e.g. drift across the hide cycle) starts a fresh upload.
            if (cloudLinkId != null && restoredUri != null) {
                runCatching {
                    val userId = accountManager.getPrimaryUserId().first()
                    val oldRow = syncStateRepo.getByCloudId(cloudLinkId)
                    if (oldRow != null && userId != null) {
                        // Drop the stale HIDDEN row (its localUri no longer exists on disk)
                        // and write a fresh SYNCED row keyed on the new MediaStore URI,
                        // carrying over the hash + backed-up timestamp so the row's history
                        // (e.g. "last sync 3 days ago") doesn't reset. deleteLocalOnlyByUris
                        // doesn't fit (status is HIDDEN here), so we re-key by upserting the
                        // new row first then nuking the old localUri via the available
                        // updateStatusAndDeleteLocal helper.
                        syncStateRepo.upsert(
                            oldRow.copy(
                                localUri = restoredUri,
                                status = SyncStatus.SYNCED,
                            ),
                            userId,
                        )
                        // Old HIDDEN row keyed on the dead URI — flip status so it stops
                        // surfacing in any "hidden cloud" listings and gets cleaned up by
                        // the next reconcile pass.
                        syncStateRepo.updateStatusAndDeleteLocal(oldRow.localUri, SyncStatus.CLOUD_ONLY)
                    }
                }
            }
            // Belt-and-suspenders refresh: the in-place filter sometimes missed when the
            // item's stored URI didn't match the hidden-set key (queryByUri normalises
            // the URI in some content-provider stacks), so the cell stayed on screen
            // until the user left and re-entered the album. Always reload from the
            // freshly-edited DataStore so the visible list = the persistent state.
            loadHiddenPhotos()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
