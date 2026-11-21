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

package eu.akoos.photos.presentation.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipLite
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.AlbumListEventBus
import eu.akoos.photos.util.combineSqlChunks
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Backs the add-to-album picker's "already in this album" indicator, and the removal a tap on such an
 * album makes.
 *
 * [GalleryAddToAlbumDialog] is shared by every surface that offers a multi-select add-to-album, and
 * the answer depends only on the selection the drawer is already handed. Resolving it here lets the
 * drawer own the whole question, so each surface shows the same indicator and offers the same removal
 * without wiring one up.
 */
@HiltViewModel
class AddToAlbumMembershipViewModel @Inject constructor(
    private val albumPhotoMembershipDao: AlbumPhotoMembershipDao,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val albumListEvents: AlbumListEventBus,
) : ViewModel() {

    private val _removing = MutableStateFlow<Set<String>>(emptySet())

    /** The albums a removal is running for, so the picker can show the mark as waiting. */
    val removing: StateFlow<Set<String>> = _removing.asStateFlow()

    /**
     * Takes [photoLinkIds] out of the album [albumLinkId]. The membership index is updated by the removal
     * itself, so the picker's mark follows from [observeSelectionAlbumMembership]; a removal that fails
     * leaves the mark in place. A second tap while one runs for the same album is ignored.
     */
    fun removeFromAlbum(albumLinkId: String, photoLinkIds: Set<String>) {
        if (photoLinkIds.isEmpty() || albumLinkId in _removing.value) return
        _removing.update { it + albumLinkId }
        viewModelScope.launch {
            try {
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                val removed = runCatching { cloudRepo.removePhotosFromAlbum(userId, albumLinkId, photoLinkIds.toList()) }
                    .onFailure { android.util.Log.w("AddToAlbumVM", "album removal failed: ${it.message}") }
                    .getOrDefault(emptyList())
                if (removed.isNotEmpty()) albumListEvents.notifyChanged()
            } finally {
                _removing.update { it - albumLinkId }
            }
        }
    }

    /**
     * Which of [photoLinkIds] each album already holds, keyed by album linkId. Local read off the
     * membership index; collected only while the picker is composed, so a selection change outside
     * it costs nothing. A read landing mid-write degrades to "no membership known" rather than
     * tearing the picker down.
     */
    fun observeSelectionAlbumMembership(photoLinkIds: Set<String>): Flow<Map<String, Set<String>>> =
        if (photoLinkIds.isEmpty()) flowOf(emptyMap())
        // Chunked: select-all hands this the whole library, past the statement's host-variable cap.
        // The query declares no ORDER BY and the rows are grouped below, so the comparator only has
        // to keep a merged read from reshuffling between emissions.
        else photoLinkIds.combineSqlChunks(
            compareBy<AlbumPhotoMembershipLite>({ it.albumLinkId }, { it.photoLinkId }),
        ) { chunk ->
            albumPhotoMembershipDao.observeAlbumIdsForPhotos(chunk.toSet())
        }
            .map { rows ->
                rows.groupBy({ it.albumLinkId }, { it.photoLinkId })
                    .mapValues { (_, ids) -> ids.toSet() }
            }
            .catch {
                android.util.Log.w("AddToAlbumVM", "selection album membership source failed: ${it.message}")
                emit(emptyMap())
            }
}
