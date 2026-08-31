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

package eu.akoos.photos.presentation.person

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceSweepEvent
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_SUGGEST_THRESHOLD
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.unpackEmbedding
import javax.inject.Inject
import kotlin.math.sqrt

/** One photo the sweep found that matches the person: shown for review, persisted only when picked. */
data class FoundPhoto(val faceId: String, val item: GalleryItem, val match: FaceSweepEvent.Match)

data class FindMoreUiState(
    val running: Boolean = true,
    val done: Int = 0,
    val total: Int = 0,
    val found: List<FoundPhoto> = emptyList(),
    val personName: String? = null,
)

/**
 * Backs [FindMorePhotosScreen]: sweeps the photos the walk found no face on at the sensitive detector
 * setting, keeps the faces that match the opened person, and offers them for review. Nothing is written
 * until the user adds a selection, so a candidate they skip never touches the index. Added faces are
 * confirmed under the person's name, so they show at once and survive a re-clustering.
 */
@HiltViewModel
class FindMorePhotosViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val getGalleryItems: GetGalleryItemsUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FindMoreUiState())
    val uiState: StateFlow<FindMoreUiState> = _uiState.asStateFlow()

    private var sweepJob: Job? = null
    private var started = false

    /** Compute the person's mean face and stream in the matches, resolving each to a gallery item for
     *  display. Runs once per screen entry. */
    fun start(personId: Long) {
        if (started) return
        started = true
        sweepJob = viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.value = _uiState.value.copy(running = false)
                return@launch
            }
            // Faces off: nothing to sweep, so end the run without touching the index.
            val prefs = context.settingsDataStore.data.first()
            val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
            if (!faceOn) { _uiState.value = _uiState.value.copy(running = false); return@launch }
            val account = userId.id
            val name = personDao.personById(personId)?.displayName
            _uiState.value = _uiState.value.copy(personName = name)

            val centroid = withContext(Dispatchers.Default) {
                meanEmbedding(faceDao.facesForPerson(account, personId))
            }
            // The person has no usable faces to match against, so there is nothing to look for.
            if (centroid == null) { _uiState.value = _uiState.value.copy(running = false); return@launch }

            // A snapshot of the library, to resolve a matched photo key to the item the grid draws.
            val library = runCatching { getGalleryItems.invoke(userId).first() }.getOrDefault(emptyList())
            val byKey = library.associateBy { it.stableId }

            // Photos already on this person (their faces, plus any manually attached), so a photo the
            // user has already added never reappears as a suggestion to add again.
            val alreadyIn = buildSet {
                addAll(faceDao.distinctPhotoKeysForPerson(account, personId))
                if (!name.isNullOrBlank()) addAll(personManualPhotoDao.photoKeysForNameList(account, name))
            }

            faceIndexingScheduler.sweepFacelessForPerson(userId, centroid, FACE_SUGGEST_THRESHOLD)
                .collect { event ->
                    when (event) {
                        is FaceSweepEvent.Progress ->
                            _uiState.value = _uiState.value.copy(done = event.done, total = event.total)
                        is FaceSweepEvent.Match -> {
                            val item = byKey[event.photoKey] ?: return@collect
                            if (item.stableId in alreadyIn) return@collect
                            val faceId = "${event.photoKey}#${event.index}"
                            // One card per photo, keeping the first (clearest) match on it.
                            if (_uiState.value.found.none { it.item.stableId == item.stableId }) {
                                _uiState.value = _uiState.value.copy(
                                    found = _uiState.value.found + FoundPhoto(faceId, item, event),
                                )
                            }
                        }
                    }
                }
            _uiState.value = _uiState.value.copy(running = false)
        }
    }

    /** Persist the picked matches as faces of the person: written with the person's id AND name, so they
     *  show now and a later re-clustering keeps them (the name carries). Refreshes the person's cover
     *  and count. */
    fun addSelected(personId: Long, selectedFaceIds: Set<String>) {
        if (selectedFaceIds.isEmpty()) return
        val picks = _uiState.value.found.filter { it.faceId in selectedFaceIds }
        if (picks.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val account = userId.id
            val name = personDao.personById(personId)?.displayName
            val rows = picks.map { p ->
                FaceEntity(
                    id = p.faceId,
                    userId = account,
                    photoKey = p.match.photoKey,
                    left = p.match.box.left, top = p.match.box.top,
                    right = p.match.box.right, bottom = p.match.box.bottom,
                    landmarks = p.match.landmarks,
                    embedding = p.match.embedding,
                    personId = personId,
                    score = p.match.score,
                    blur = p.match.blur,
                    manualName = name?.takeIf { it.isNotBlank() },
                )
            }
            faceDao.upsert(rows)
            // Count the person the same way every other path does: their face photos unioned with any
            // manually attached ones, de-duplicated, so a person carrying manual adds is not under-counted.
            val manual = if (name.isNullOrBlank()) emptyList()
                else personManualPhotoDao.photoKeysForNameList(account, name)
            val count = personPhotoCount(faceDao.distinctPhotoKeysForPerson(account, personId), manual)
            val cover = personDao.personById(personId)?.coverFaceId ?: faceDao.topFaceForPerson(account, personId)
            personDao.updateCoverAndCount(personId, cover, count)
        }
    }

    /** L2-normalised mean of a person's face embeddings, or null when none are usable. */
    private fun meanEmbedding(faces: List<FaceEntity>): FloatArray? {
        var sum: DoubleArray? = null
        var used = 0
        for (f in faces) {
            val e = unpackEmbedding(f.embedding)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val acc = sum ?: DoubleArray(e.size).also { sum = it }
            for (i in e.indices) acc[i] += e[i]
            used++
        }
        val acc = sum ?: return null
        if (used == 0) return null
        var norm = 0.0
        for (v in acc) norm += v * v
        val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
        return FloatArray(acc.size) { (acc[it] * inv).toFloat() }
    }
}
