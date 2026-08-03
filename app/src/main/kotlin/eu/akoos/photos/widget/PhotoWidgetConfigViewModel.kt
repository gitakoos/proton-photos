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

package eu.akoos.photos.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.CloudAlbumEntity
import eu.akoos.photos.data.db.entity.PhotoPickerRow
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** How many times the picker's cloud-photo stream may re-subscribe before the failure reaches the
 *  screen, matching the album grid's cap. Enough to ride out a torn cursor window while a sync writes
 *  the same table, too few to mask a read that cannot succeed at all: that one would otherwise leave
 *  the picker spinning with nothing said. */
private const val CLOUD_PHOTOS_MAX_RETRIES = 5L

data class WidgetConfigUiState(
    val mode: WidgetMode         = WidgetMode.ALL_PHOTOS,
    val interval: WidgetInterval = WidgetInterval.ONE_HOUR,
    val selectedUris: List<String> = emptyList(),
    val selectedAlbum: String?   = null,
    val albums: List<LocalAlbum> = emptyList(),
    /**
     * Device photos offered for [WidgetMode.SELECTED], newest-first. Images only:
     * the widget renders images, so videos are filtered out. Each item's uri is the
     * MediaStore content:// uri Coil loads directly (no decrypt needed).
     */
    val devicePhotos: List<LocalMediaItem> = emptyList(),
    /**
     * Pool of cloud photos available for [WidgetMode.CLOUD_SELECTED], newest first. Each entry is the
     * two-column picker projection: a link id and the (possibly null) decrypted thumbnailUrl — null
     * means no cell has decrypted this photo yet, so its thumbnail is not in the app cache. The widget
     * worker will request it lazily.
     */
    val cloudPhotos: List<PhotoPickerRow> = emptyList(),
    val selectedLinkIds: List<String> = emptyList(),
    /** Cloud albums the widget can follow in [WidgetMode.CLOUD_ALBUM]. */
    val cloudAlbums: List<CloudAlbumEntity> = emptyList(),
    /** linkId of the album chosen for [WidgetMode.CLOUD_ALBUM], or null when none picked. */
    val selectedCloudAlbumLinkId: String? = null,
    /** True until the first cloud-photo emission lands, so the picker can show a spinner
     *  instead of a premature empty state on a cold DB. */
    val isLoadingCloud: Boolean  = true,
    /** Sanitized reason the cloud-photo stream stopped, set when it failed past its retry cap. Shown
     *  in place of the empty-library line so an unreadable library never reads as an empty one. */
    val cloudError: String?      = null,
    val isSaving: Boolean        = false,
    val saved: Boolean           = false,
)

@HiltViewModel
class PhotoWidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
    private val photoListingDao: PhotoListingDao,
    private val cloudAlbumDao: CloudAlbumDao,
    private val driveRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetConfigUiState())
    val state: StateFlow<WidgetConfigUiState> = _state.asStateFlow()

    /** Tracks whether [loadFor] has already populated state from Glance, so we don't
     *  overwrite the user's in-progress edits when the screen recomposes. */
    private var hydrated = false

    init {
        observeAlbums()
        observeCloudPhotos()
        observeCloudAlbums()
        warmCloudAlbums()
    }

    /**
     * Pre-fill the form from the widget's existing Glance state. Idempotent — calling
     * twice for the same widget id is a no-op. Lets the user edit a placed widget's
     * config without having to remove + re-add it.
     *
     * Silently no-ops on first-config (no state yet) — the defaults from
     * [WidgetConfigUiState] keep the form in its blank state.
     */
    fun loadFor(appWidgetId: Int) {
        if (hydrated) return
        hydrated = true
        viewModelScope.launch {
            val glanceId = runCatching {
                GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            }.getOrNull() ?: return@launch
            val prefs = runCatching {
                getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            }.getOrNull() ?: return@launch

            val modeName = prefs[PhotoWidgetKeys.MODE]
            val mode = if (modeName != null) {
                runCatching { WidgetMode.valueOf(modeName) }.getOrDefault(WidgetMode.ALL_PHOTOS)
            } else WidgetMode.ALL_PHOTOS

            val urisRaw = prefs[PhotoWidgetKeys.SELECTED_URIS] ?: ""
            val uris = if (urisRaw.isBlank()) emptyList()
                else urisRaw.split(PhotoWidgetKeys.URI_SEPARATOR).filter { it.isNotBlank() }
            val linkIdsRaw = prefs[PhotoWidgetKeys.SELECTED_LINK_IDS] ?: ""
            val linkIds = if (linkIdsRaw.isBlank()) emptyList()
                else linkIdsRaw.split(PhotoWidgetKeys.URI_SEPARATOR).filter { it.isNotBlank() }
            val album = prefs[PhotoWidgetKeys.ALBUM_NAME]?.takeIf { it.isNotBlank() }
            val cloudAlbumLinkId = prefs[PhotoWidgetKeys.CLOUD_ALBUM_LINK_ID]?.takeIf { it.isNotBlank() }
            val intervalMin = prefs[PhotoWidgetKeys.INTERVAL_MINUTES]
            val interval = WidgetInterval.entries.firstOrNull { it.minutes == intervalMin }
                ?: WidgetInterval.ONE_HOUR

            _state.update {
                it.copy(
                    mode = mode,
                    interval = interval,
                    selectedUris = uris,
                    selectedLinkIds = linkIds,
                    selectedAlbum = album,
                    selectedCloudAlbumLinkId = cloudAlbumLinkId,
                )
            }
        }
    }

    /**
     * Stream the user's cloud photo listing into [WidgetConfigUiState.cloudPhotos], newest first.
     *
     * The two-column picker projection, not the whole rows: the grid keys on the link id and binds the
     * thumbnail, while a full read would carry every row's armored crypto blocks (kilobytes each) into
     * ViewModel state the moment the widget is added. A cell whose thumbnail is still null asks for a
     * decrypt through [requestCloudThumbnailDecrypt], which hydrates that one row's crypto material by
     * link id. Newest-first is the query's own ORDER BY, so no second sorted copy of the library. The
     * widget worker handles the lazy decrypt on its own when the widget cycles to a
     * not-yet-materialised entry.
     */
    private fun observeCloudPhotos() {
        viewModelScope.launch {
            val userId: UserId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Own stream only — photos from shared-with-me albums must not be offered as
            // widget content.
            photoListingDao.observeOwnStreamPickerRows(userId.id)
                // A read that lands mid-write can throw a transient CursorWindow error, so
                // re-subscribe rather than let it reach the collector. Capped, because a read that
                // keeps failing is not a torn window and no number of retries will fix it.
                .retryOnDbTear("WidgetConfigVM", maxAttempts = CLOUD_PHOTOS_MAX_RETRIES)
                // Terminal failure of the stream, past its retry cap. Surfacing it drops the spinner
                // and says what went wrong, where a swallowed one leaves the picker loading forever.
                .catch { e ->
                    Log.e("WidgetConfigVM", "widget photo stream gave up", e)
                    _state.update {
                        it.copy(isLoadingCloud = false, cloudError = sanitizeErrorMessage(e.message))
                    }
                }
                .collectLatest { rows ->
                    // Clear the loading flag on the first (and every) emission so the picker
                    // leaves its spinner state once real data (even an empty list) has arrived.
                    _state.update { it.copy(cloudPhotos = rows, isLoadingCloud = false, cloudError = null) }
                }
        }
    }

    /**
     * Stream the cached cloud-album list into [WidgetConfigUiState.cloudAlbums] for the
     * follow-an-album ([WidgetMode.CLOUD_ALBUM]) picker. DB-backed, so a cold cache shows
     * nothing until [warmCloudAlbums] refreshes it from the network.
     */
    private fun observeCloudAlbums() {
        viewModelScope.launch {
            cloudAlbumDao.observeOwned().collectLatest { albums ->
                _state.update { it.copy(cloudAlbums = albums) }
            }
        }
    }

    /**
     * Best-effort network refresh so a cold DB populates the cloud-album picker. Non-blocking
     * and failure-swallowing. The observed DB stream is the source of truth; this just warms it.
     */
    private fun warmCloudAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.loadAlbums(userId) }
        }
    }

    /** Push a thumbnail decrypt request through the scheduler so the picker can render the cell as
     *  soon as the bytes land in the on-disk cache. Keyed by link id: the repository looks that one
     *  row's encrypted material up, which is what lets the picker's own read skip it. Deduped by the
     *  scheduler, so a re-run on recomposition is free. */
    fun requestCloudThumbnailDecrypt(linkId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            driveRepo.requestThumbnailDecrypt(userId, linkId)
        }
    }

    /**
     * Single MediaStore collection feeding both the album picker ([WidgetMode.ALBUM]) and the
     * device-photo grid ([WidgetMode.SELECTED]). Sharing one collector avoids a second identical
     * observeLocalMedia() subscription. Device photos are filtered to images and sorted
     * newest-first for the in-app selectable grid.
     */
    private fun observeAlbums() {
        viewModelScope.launch {
            localMediaRepo.observeLocalMedia().collectLatest { items ->
                val albums = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, grouped) ->
                        val sorted = grouped.sortedByDescending { it.dateTaken }
                        LocalAlbum(
                            name      = name,
                            coverUri  = sorted.firstOrNull()?.uri,
                            itemCount = sorted.size,
                            items     = sorted,
                        )
                    }
                    .sortedByDescending { it.items.firstOrNull()?.dateTaken ?: 0L }
                val devicePhotos = items
                    .filter { it.mimeType.startsWith("image/") }
                    .sortedByDescending { it.dateTaken }
                _state.update { it.copy(albums = albums, devicePhotos = devicePhotos) }
            }
        }
    }

    fun setMode(mode: WidgetMode) = _state.update { it.copy(mode = mode) }

    fun setInterval(interval: WidgetInterval) = _state.update { it.copy(interval = interval) }

    fun setSelectedUris(uris: List<String>) = _state.update { it.copy(selectedUris = uris) }

    fun setAlbum(albumName: String) = _state.update { it.copy(selectedAlbum = albumName) }

    fun setSelectedLinkIds(linkIds: List<String>) = _state.update { it.copy(selectedLinkIds = linkIds) }

    fun setCloudAlbum(albumLinkId: String) = _state.update { it.copy(selectedCloudAlbumLinkId = albumLinkId) }

    /**
     * Persist widget state to Glance DataStore, schedule workers, and signal "done".
     */
    fun save(appWidgetId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val s       = _state.value
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

                // Write all config into Glance PreferencesGlanceStateDefinition
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                    p.toMutablePreferences().also { mp ->
                        mp[PhotoWidgetKeys.MODE]             = s.mode.name
                        mp[PhotoWidgetKeys.ALBUM_NAME]       = s.selectedAlbum ?: ""
                        mp[PhotoWidgetKeys.INTERVAL_MINUTES] = s.interval.minutes
                        mp[PhotoWidgetKeys.CURRENT_INDEX]    = 0
                        if (s.mode == WidgetMode.CLOUD_ALBUM) {
                            // Follow-the-album persists only the album id; members resolve live at
                            // update time. Clear the fixed cloud-selection list so a mode switch
                            // doesn't leave stale linkIds behind.
                            mp[PhotoWidgetKeys.CLOUD_ALBUM_LINK_ID] = s.selectedCloudAlbumLinkId ?: ""
                            mp[PhotoWidgetKeys.SELECTED_LINK_IDS]   = ""
                        } else {
                            mp[PhotoWidgetKeys.SELECTED_LINK_IDS] = s.selectedLinkIds
                                .joinToString(PhotoWidgetKeys.URI_SEPARATOR)
                            mp[PhotoWidgetKeys.CLOUD_ALBUM_LINK_ID] = ""
                        }
                        mp[PhotoWidgetKeys.SELECTED_URIS]    = s.selectedUris
                            .joinToString(PhotoWidgetKeys.URI_SEPARATOR)
                    }
                }

                // Fire an immediate first update so the widget shows a photo right away
                PhotoWidgetUpdateWorker.enqueueImmediate(context, appWidgetId)
                // Schedule the recurring interval update
                PhotoWidgetUpdateWorker.enqueueOrReplace(context, appWidgetId, s.interval.minutes)

                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (_: Exception) {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}
