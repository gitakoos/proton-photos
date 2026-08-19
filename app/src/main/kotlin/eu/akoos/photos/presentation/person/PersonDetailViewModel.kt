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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_SUGGEST_THRESHOLD
import eu.akoos.photos.domain.usecase.MIN_FACES_TO_SHOW_PERSON
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.domain.usecase.unpackEmbedding
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.PersonUi
import javax.inject.Inject
import kotlin.math.sqrt

data class PersonDetailUiState(
    /** The person's given name, or null while none is set (the screen shows a fallback then). */
    val personName: String? = null,
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
)

/**
 * Backs [PersonDetailScreen]: an album-style, full-screen grid of every photo one clustered person
 * appears in. The person's photo keys come from the face index (excluding faces the user removed) plus
 * any photos manually attached to the person's name, and each key resolves to its [GalleryItem] from
 * the same merged library the gallery / search / calendar open the viewer with, so every cell carries
 * the right synced / cloud state. Observed live, so a manual add or removal shows immediately.
 *
 * Curation stays sticky across a clustering rebuild: a removal rejects the underlying faces (never
 * reclustered back in), and a manual add is stored against the person's NAME, which survives a rebuild.
 */
@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val assignPersonName: eu.akoos.photos.domain.usecase.AssignPersonNameUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    /** One-shot user feedback (a string res id) for actions with no visible result of their own, such
     *  as trying to set a cover from a photo that has no detected face. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    /** Other people, ranked most-alike first, offered as merge targets when the user picks "Merge". */
    private val _mergeCandidates = MutableStateFlow<List<PersonUi>>(emptyList())
    val mergeCandidates: StateFlow<List<PersonUi>> = _mergeCandidates.asStateFlow()

    /** The most-likely other cluster this named person could be the same as, shown as a top-of-page
     *  prompt to merge, dismiss, or leave. One at a time (the closest): resolving it surfaces the next.
     *  Null when there is nothing above the suggestion bar or the user has dismissed everything. */
    private val _mergeSuggestion = MutableStateFlow<PersonUi?>(null)
    val mergeSuggestion: StateFlow<PersonUi?> = _mergeSuggestion.asStateFlow()

    /** The person's current photo keys, so the add picker can hide photos they already hold. */
    private val _keysForPicker = MutableStateFlow<Set<String>>(emptySet())
    val keysForPicker: StateFlow<Set<String>> = _keysForPicker.asStateFlow()

    private var loadJob: Job? = null
    private var keysJob: Job? = null

    /**
     * Observe the person's photos: the faces they appear in (minus removed ones) unioned with any
     * manually attached photos, each resolved to a [GalleryItem] newest-first. Keyed on the person id
     * so re-entering a different person reloads cleanly, and re-run after a rename so manual adds keyed
     * on the new name are picked up.
     */
    fun load(personId: Long) {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            try {
                val userId = accountManager.getPrimaryUserId().first() ?: run {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                // Faces off: read no face data and show an empty grid.
                val prefs = context.settingsDataStore.data.first()
                val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
                if (!faceOn) {
                    _uiState.update { it.copy(isLoading = false, items = emptyList()) }
                    return@launch
                }
                val name = personDao.personById(personId)?.displayName
                _uiState.update { it.copy(personName = name) }

                val manualKeys = if (name.isNullOrBlank()) flowOf(emptyList())
                    else personManualPhotoDao.photoKeysForName(userId.id, name)

                combine(
                    faceDao.photoKeysForPerson(userId.id, personId),
                    manualKeys,
                    getGalleryItems.invoke(userId),
                ) { faceKeys, added, library ->
                    val keys = (faceKeys + added).toSet()
                    library.filter { it.stableId in keys }
                        .sortedByDescending { it.captureTimeMs }
                }.collect { matched ->
                    _uiState.update { it.copy(isLoading = false, items = matched) }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A transient DB / library read must end the spinner rather than shimmer forever.
                _uiState.update { it.copy(isLoading = false, items = emptyList()) }
            }
        }
    }

    /** Observe the person's current photo keys (faces minus removed, plus manual adds), so the add
     *  picker can exclude what the person already holds. */
    fun loadKeys(personId: Long) {
        keysJob?.cancel()
        keysJob = viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val prefs = context.settingsDataStore.data.first()
            val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
            if (!faceOn) { _keysForPicker.value = emptySet(); return@launch }
            val name = personDao.personById(personId)?.displayName
            val manual = if (name.isNullOrBlank()) flowOf(emptyList())
                else personManualPhotoDao.photoKeysForName(userId.id, name)
            combine(faceDao.photoKeysForPerson(userId.id, personId), manual) { faceKeys, added ->
                (faceKeys + added).toSet()
            }.collect { _keysForPicker.value = it }
        }
    }

    /** Load the merge picker: every other person, ordered by how closely their mean face matches this
     *  person's, so a likely duplicate sits first. */
    fun loadMergeCandidates(personId: Long) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val centroids = withContext(Dispatchers.Default) {
                centroidsByPerson(faceDao.allFacesByScoreDesc(userId.id))
            }
            val target = centroids[personId]
            val people = observePeopleUseCase(userId, getGalleryItems.invoke(userId)).first()
                .mapNotNull { it.toPersonUi() }
            _mergeCandidates.value = people
                .filter { it.personId != personId && !it.displayName.isNullOrBlank() }
                .sortedByDescending { c ->
                    val a = target ?: return@sortedByDescending -1f
                    val b = centroids[c.personId] ?: return@sortedByDescending -1f
                    cosineSimilarity(a, b)
                }
        }
    }

    /**
     * Fold THIS person into the person named [rawName]: an existing person of that name absorbs this
     * one's faces (confirmed under the name so the merge survives a rebuild) and its name-keyed curation
     * re-keys across, then this now-empty person is removed. If no person of that name exists, this
     * person is simply renamed to it. The caller navigates away, since this person may be gone.
     */
    fun mergeWith(personId: Long, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: return@launch
            val target = foldPersonInto(account, personId, name)
            if (target == personId) _uiState.update { it.copy(personName = name) }
        }
    }

    /**
     * Fold the person [fromPersonId] into the person named [name]: the existing person of that name
     * absorbs its faces (relabelled so the merge survives a rebuild) and its name-keyed curation, then
     * the now-empty source is removed. Returns the surviving person's id. Shared by an explicit merge
     * and by accepting a merge suggestion, so both keep the exact same bookkeeping.
     */
    private suspend fun foldPersonInto(account: String, fromPersonId: Long, name: String): Long =
        assignPersonName(account, fromPersonId, name)

    /**
     * Find the best "might also be this person" merge suggestion for [personId] (a named person): the
     * closest OTHER cluster whose mean face clears [FACE_SUGGEST_THRESHOLD], skipping any whose faces the
     * user already marked "not this person". Singletons are eligible, since a lone side-profile shot is
     * exactly what the automatic grouping leaves separate. Shows one at a time, closest first.
     */
    fun loadMergeSuggestion(personId: Long) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val account = userId.id
            val name = personDao.personById(personId)?.displayName
            if (name.isNullOrBlank()) { _mergeSuggestion.value = null; return@launch }
            val bestId = withContext(Dispatchers.Default) {
                val faces = faceDao.allFacesByScoreDesc(account)
                val centroids = centroidsByPerson(faces)
                val target = centroids[personId] ?: return@withContext -1L
                val rejectedFaceIds = notPersonDao.allForUser(account)
                    .filter { it.personName == name }.mapTo(HashSet()) { it.faceId }
                val faceIdsByPerson = HashMap<Long, MutableSet<String>>()
                for (f in faces) {
                    if (f.rejected) continue
                    val pid = f.personId ?: continue
                    faceIdsByPerson.getOrPut(pid) { HashSet() }.add(f.id)
                }
                var chosen = -1L
                var bestSim = FACE_SUGGEST_THRESHOLD
                for ((pid, c) in centroids) {
                    if (pid == personId) continue
                    // Never re-offer a cluster the user already said is not this person.
                    if (faceIdsByPerson[pid]?.any { it in rejectedFaceIds } == true) continue
                    val s = cosineSimilarity(target, c)
                    if (s > bestSim) { bestSim = s; chosen = pid }
                }
                chosen
            }
            if (bestId < 0L) { _mergeSuggestion.value = null; return@launch }
            _mergeSuggestion.value = observePeopleUseCase(userId, getGalleryItems.invoke(userId)).first()
                .firstOrNull { it.personId == bestId }?.toPersonUi()
        }
    }

    /** Accept the current merge suggestion: fold [candidateId] into this named person, then look for the
     *  next candidate. This person stays; the candidate is absorbed. */
    fun acceptMergeSuggestion(personId: Long, candidateId: Long) {
        val name = _uiState.value.personName?.trim().orEmpty()
        if (name.isEmpty()) return
        _mergeSuggestion.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: return@launch
            foldPersonInto(account, candidateId, name)
            loadMergeSuggestion(personId)
        }
    }

    /** Dismiss the current merge suggestion for good: record the candidate's faces as "not this person"
     *  (name + faceId, so it survives a rebuild and is never offered or pulled in again), then look for
     *  the next candidate. */
    fun dismissMergeSuggestion(personId: Long, candidateId: Long) {
        val name = _uiState.value.personName?.trim().orEmpty()
        if (name.isEmpty()) return
        _mergeSuggestion.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: return@launch
            val faceIds = faceDao.faceIdsForPerson(account, candidateId)
            if (faceIds.isNotEmpty()) notPersonDao.add(faceIds.map { NotPersonEntity(account, name, it) })
            loadMergeSuggestion(personId)
        }
    }

    /** Leave the current suggestion for now: hide it without recording anything, so it can surface again
     *  the next time the person is opened. */
    fun clearMergeSuggestion() { _mergeSuggestion.value = null }

    /** L2-normalised mean embedding per person id (rejected faces excluded), a person's centroid. */
    private fun centroidsByPerson(faces: List<eu.akoos.photos.data.db.entity.FaceEntity>): Map<Long, FloatArray> {
        val sums = HashMap<Long, DoubleArray>()
        for (f in faces) {
            if (f.rejected) continue
            val pid = f.personId ?: continue
            val e = unpackEmbedding(f.embedding)
            if (e.size != FACE_EMBEDDING_DIM) continue
            val acc = sums.getOrPut(pid) { DoubleArray(e.size) }
            for (i in e.indices) acc[i] += e[i]
        }
        val out = HashMap<Long, FloatArray>()
        for ((pid, acc) in sums) {
            var norm = 0.0
            for (v in acc) norm += v * v
            val inv = if (norm > 0.0) 1.0 / sqrt(norm) else 0.0
            out[pid] = FloatArray(acc.size) { (acc[it] * inv).toFloat() }
        }
        return out
    }

    private fun PersonSummary.toPersonUi(): PersonUi? {
        val cover = coverPhotoKey ?: return null
        return PersonUi(
            personId = personId,
            displayName = displayName,
            coverPhotoKey = cover,
            faceBox = faceBox?.let { FaceBox(it.left, it.top, it.right, it.bottom) },
            faceCount = faceCount,
        )
    }

    /** Rename the person, clearing the name back to null on empty input. Moves any manual memberships
     *  onto the new name so added photos follow the rename, then re-observes under it. */
    fun rename(personId: Long, newName: String) {
        val trimmed = newName.trim().ifEmpty { null }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val old = personDao.personById(personId)?.displayName
            personDao.updateName(personId, trimmed)
            if (!old.isNullOrBlank() && !trimmed.isNullOrBlank() && old != trimmed) {
                personManualPhotoDao.rename(userId.id, old, trimmed)
                faceDao.renameManualName(userId.id, old, trimmed)
                notPersonDao.rename(userId.id, old, trimmed)
                personCoverDao.rename(userId.id, old, trimmed)
            }
            _uiState.update { it.copy(personName = trimmed) }
            load(personId)
        }
    }

    /**
     * Remove the given photos from this person. The person's faces in those photos are rejected, so
     * they drop off now and are never reclustered back in, and any manual membership is detached. The
     * cached cover and count are refreshed so the People list stays honest.
     */
    fun removePhotos(personId: Long, photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val name = personDao.personById(personId)?.displayName
            // "Not this person", not "delete": record the exact faces as rejected for this name (so they
            // never rejoin), then unassign them WITHOUT rejecting, so they recluster and can be
            // suggested for the right person on the next rebuild rather than vanishing from People.
            val faceIds = faceDao.faceIdsForPersonInPhotos(userId.id, personId, photoKeys)
            if (!name.isNullOrBlank() && faceIds.isNotEmpty()) {
                notPersonDao.add(faceIds.map { NotPersonEntity(userId.id, name, it) })
            }
            faceDao.unassignFaces(faceIds)
            if (!name.isNullOrBlank()) personManualPhotoDao.remove(userId.id, name, photoKeys)
            val manual = if (name.isNullOrBlank()) emptyList()
                else personManualPhotoDao.photoKeysForNameList(userId.id, name)
            val count = (faceDao.distinctPhotoKeysForPerson(userId.id, personId) + manual).toHashSet().size
            val cover = resolveCover(userId.id, personId, name)
            personDao.updateCoverAndCount(personId, cover, count)
        }
    }

    /** "This is not a person": reject every face in the cluster so it never groups again, drop the
     *  name-keyed curation, and remove the person. For dismissing a junk or group-photo cluster.
     *  [onDone] runs on the main thread so the screen can pop back. */
    fun ignorePerson(personId: Long, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val name = personDao.personById(personId)?.displayName
            faceDao.rejectAllForPerson(userId.id, personId)
            if (!name.isNullOrBlank()) {
                personManualPhotoDao.clearForName(userId.id, name)
                notPersonDao.clearForName(userId.id, name)
                personCoverDao.clearForName(userId.id, name)
            }
            personDao.updateCoverAndCount(personId, null, 0)
            personDao.deleteEmpty(userId.id)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /**
     * Attach photos to this person so they show under it and survive a rescan. Stored against the
     * person's name (the stable key across a rebuild), so the person must be named first.
     */
    fun addPhotos(personId: Long, photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val name = personDao.personById(personId)?.displayName
            if (name.isNullOrBlank()) return@launch
            personManualPhotoDao.add(photoKeys.map { PersonManualPhotoEntity(userId.id, name, it) })
        }
    }

    /**
     * Set the given photo as this person's cover. Applies now, and for a named person is also stored
     * against the name so the choice survives a clustering rebuild (resolved back to that person's face
     * on the photo). A photo with no detected face for this person cannot be a cover, so nothing changes
     * and a short message explains why.
     */
    fun setCover(personId: Long, photoKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val account = userId.id
            val faceId = faceDao.topFaceForPersonPhoto(account, personId, photoKey)
            if (faceId == null) {
                _message.value = R.string.person_cover_no_face
                return@launch
            }
            personDao.updateCover(personId, faceId)
            val name = personDao.personById(personId)?.displayName
            if (!name.isNullOrBlank()) {
                personCoverDao.set(PersonCoverEntity(account, name, photoKey))
            }
        }
    }

    /** The person's cover face: their chosen cover when its photo is still one of their faces, else the
     *  clearest face. Used wherever the cached cover is recomputed after curation. */
    private suspend fun resolveCover(account: String, personId: Long, name: String?): String? {
        val chosen = name?.takeIf { it.isNotBlank() }
            ?.let { personCoverDao.photoKeyForName(account, it) }
            ?.let { key -> faceDao.topFaceForPersonPhoto(account, personId, key) }
        return chosen ?: faceDao.topFaceForPerson(account, personId)
    }
}
