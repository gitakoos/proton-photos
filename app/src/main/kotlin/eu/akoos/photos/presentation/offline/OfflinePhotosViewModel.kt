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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.offline

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.common.SelectionState
import javax.inject.Inject

/**
 * Backs the dedicated "Offline photos" screen. Observes the shared (already-merged, newest-first)
 * gallery feed and the OFFLINE_PIN_IDS set, and exposes the cloud photos pinned for offline. The
 * merge is the @Singleton [GetGalleryItemsUseCase], so this adds no second full-library pass.
 */
@HiltViewModel
class OfflinePhotosViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val offlineStore: OfflineStorageManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Latest primary userId, captured for the thumbnail-decrypt requests below.
    private var primaryUserId: UserId? = null

    private val offlinePinIdsFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
    }

    /** Pinned cloud photos in the gallery's own order (newest-first). A pinned item is a
     *  [GalleryItem.CloudOnly] whose linkId is in OFFLINE_PIN_IDS; the merged feed already drops to
     *  the empty list when no user is signed in. */
    val items: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(getGalleryItems.invoke(userId), offlinePinIdsFlow) { all, pins ->
                all.filter { (it as? GalleryItem.CloudOnly)?.cloud?.linkId?.let { id -> id in pins } == true }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cloud linkIds the user has selected. Selection mode is active whenever this is non-empty. */
    private val selection = SelectionState<String>()
    val selectedIds: StateFlow<Set<String>> = selection.flow

    /** True while at least one cell is selected — tapping then toggles instead of opening the viewer. */
    val inSelectionMode: StateFlow<Boolean> = selection.flow
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        accountManager.getPrimaryUserId().onEach { primaryUserId = it }.launchIn(viewModelScope)
    }

    /** Enqueue an on-demand thumbnail decrypt; deduped by linkId, no-op until primaryUserId lands. */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /** Cancel any in-flight decrypt for [linkId] when its cell scrolls off-screen. */
    fun cancelThumbnailDecrypt(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
    }

    fun toggleSelection(linkId: String) = selection.toggle(linkId)

    fun clearSelection() = selection.clear()

    /** Replace the whole selection — the drag-to-select sweep paints the swept range through here. */
    fun setSelected(ids: Set<String>) = selection.set(ids)

    /**
     * Removes every selected photo from offline storage: drops their ids from
     * [SettingsKeys.OFFLINE_PIN_IDS] in one edit and deletes each blob, then clears the selection.
     * The cloud photos themselves are untouched — only the on-device pin and blob go. The [items]
     * flow observes the pin set, so the removed cells drop out on their own.
     */
    fun removeSelectedFromOffline() {
        val snapshot = selection.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - snapshot
            }
            snapshot.forEach { offlineStore.delete(it) }
            clearSelection()
        }
    }
}
