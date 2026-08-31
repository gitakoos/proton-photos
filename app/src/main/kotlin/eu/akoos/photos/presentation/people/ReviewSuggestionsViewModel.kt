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

package eu.akoos.photos.presentation.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.PersonUi
import javax.inject.Inject

/**
 * Backs the review screen: the clusters the app has found but the user has not named yet, shown as a
 * grid (biggest first) so they can be curated in bulk. Selecting several and MERGING folds them into
 * one named person and confirms their faces, so a person split across a few clusters (the same face
 * from the side, in different light) becomes one; selecting junk and marking NOT A PERSON drops those
 * faces from every future pass. A single cluster still opens for naming on its own.
 */
@HiltViewModel
class ReviewSuggestionsViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
) : ViewModel() {

    /** One-shot user feedback (a string res id) for a bulk action that otherwise finishes with no
     *  confirmation of its own. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    /** Every unnamed cluster, most-photographed first, one card each. Null until the first emission,
     *  so the screen shows a skeleton rather than the empty state while it loads. */
    val clusters: StateFlow<List<PersonUi>?> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                observePeopleUseCase(userId, getGalleryItems.invoke(userId)).map { list ->
                    list.mapNotNull { it.toPersonUi() }
                        .filter { it.displayName.isNullOrBlank() }
                        // The Unsorted bucket is pinned to the top, then the real unnamed clusters by size,
                        // so the leftover pile is the first thing offered for curation but stays apart.
                        .sortedWith(
                            compareByDescending<PersonUi> { it.isOther }.thenByDescending { it.faceCount },
                        )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Already-named people, so a bulk merge can fold the selection into an EXISTING person, not only
     *  a new name. Feeds the shared person picker's list. */
    val namedPeople: StateFlow<List<PersonUi>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                observePeopleUseCase(userId, getGalleryItems.invoke(userId)).map { list ->
                    list.mapNotNull { it.toPersonUi() }
                        .filter { !it.displayName.isNullOrBlank() }
                        .sortedByDescending { it.faceCount }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Fold the selected clusters into one named person. Reuses an existing person of that name if one
     * exists, else the largest selected cluster; every selected face moves onto it and is confirmed
     * under the name (so the merge survives a rebuild and pulls in matching faces), and the emptied
     * clusters are removed.
     */
    fun bulkMerge(clusterIds: Set<Long>, rawName: String) {
        val name = rawName.trim()
        if (clusterIds.isEmpty() || name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val account = userId.id
            val existing = personDao.namedPeopleForUser(account).firstOrNull { it.displayName == name }
            val target = existing?.id
                ?: clusterIds.maxByOrNull { faceDao.faceCountForPerson(account, it) }
                ?: return@launch
            val faceIds = clusterIds.flatMap { faceDao.faceIdsForPerson(account, it) }
            if (faceIds.isNotEmpty()) {
                faceDao.reassignFacesToPerson(faceIds, target)
                faceDao.labelFacesByIds(faceIds, name)
            }
            personDao.updateName(target, name)
            val manual = personManualPhotoDao.photoKeysForNameList(account, name)
            val count = (faceDao.distinctPhotoKeysForPerson(account, target) + manual).toHashSet().size
            val cover = faceDao.topFaceForPerson(account, target)
            personDao.updateCoverAndCount(target, cover, count)
            for (id in clusterIds) if (id != target) personDao.updateCoverAndCount(id, null, 0)
            personDao.deleteEmpty(account)
            _message.value = R.string.person_msg_merged
        }
    }

    /**
     * Mark the selected clusters as "not a person" (a statue, a poster, a repeated false detection):
     * their faces are rejected, so they leave People and are skipped by every future clustering pass.
     */
    fun bulkNotPerson(clusterIds: Set<Long>) {
        if (clusterIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            for (id in clusterIds) {
                faceDao.rejectAllForPerson(userId.id, id)
                personDao.updateCoverAndCount(id, null, 0)
            }
            personDao.deleteEmpty(userId.id)
            _message.value = R.string.person_msg_dismissed
        }
    }

    private fun PersonSummary.toPersonUi(): PersonUi? {
        val cover = coverPhotoKey ?: return null
        return PersonUi(
            personId = personId,
            displayName = displayName,
            coverPhotoKey = cover,
            faceBox = faceBox?.let { FaceBox(it.left, it.top, it.right, it.bottom) },
            faceCount = faceCount,
            isOther = isOther,
        )
    }
}
