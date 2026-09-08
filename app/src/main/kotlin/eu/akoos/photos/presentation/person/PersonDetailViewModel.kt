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
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_SUGGEST_THRESHOLD
import eu.akoos.photos.domain.usecase.MIN_FACES_TO_SHOW_PERSON
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.domain.usecase.unpackEmbedding
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.toPersonUi
import javax.inject.Inject
import kotlin.math.sqrt

data class PersonDetailUiState(
    /** The person's given name, or null while none is set (the screen shows a fallback then). */
    val personName: String? = null,
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
    /** True for the single "Unsorted" bucket, so the screen labels it, explains it, and offers only
     *  curation (move a real person out, mark the rest not a person) rather than naming the whole pile. */
    val isOther: Boolean = false,
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
    private val faceIndexingScheduler: eu.akoos.photos.data.face.FaceIndexingScheduler,
    private val hideSimilarFaces: eu.akoos.photos.domain.usecase.HideSimilarFacesUseCase,
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
                val userId = accountManager.getPrimaryUserId().first()
                val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
                // Faces off: read no face data and show an empty grid.
                val prefs = context.settingsDataStore.data.first()
                val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
                if (!faceOn) {
                    _uiState.update { it.copy(isLoading = false, items = emptyList()) }
                    return@launch
                }
                val person = personDao.personById(personId)
                val name = person?.displayName
                _uiState.update { it.copy(personName = name, isOther = person?.isOther == true) }

                val manualKeys = if (name.isNullOrBlank()) flowOf(emptyList())
                    else personManualPhotoDao.photoKeysForName(account, name)

                combine(
                    faceDao.photoKeysForPerson(account, personId),
                    manualKeys,
                    if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
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
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val prefs = context.settingsDataStore.data.first()
            val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
            if (!faceOn) { _keysForPicker.value = emptySet(); return@launch }
            val name = personDao.personById(personId)?.displayName
            val manual = if (name.isNullOrBlank()) flowOf(emptyList())
                else personManualPhotoDao.photoKeysForName(account, name)
            combine(faceDao.photoKeysForPerson(account, personId), manual) { faceKeys, added ->
                (faceKeys + added).toSet()
            }.collect { _keysForPicker.value = it }
        }
    }

    /** Load the merge picker: every other person, ordered by how closely their mean face matches this
     *  person's, so a likely duplicate sits first. */
    fun loadMergeCandidates(personId: Long) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val centroids = withContext(Dispatchers.Default) {
                centroidsByPerson(faceDao.allFacesByScoreDesc(account))
            }
            val target = centroids[personId]
            val people = observePeopleUseCase(
                userId,
                if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
            ).first().mapNotNull { it.toPersonUi() }
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
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val target = foldPersonInto(account, personId, name)
            // The merged name may match faces still sitting in other clusters; re-cluster so they join now.
            faceIndexingScheduler.requestRecluster(userId)
            if (target == personId) {
                _uiState.update { it.copy(personName = name) }
                _message.value = R.string.person_msg_renamed
            } else {
                _message.value = R.string.person_msg_merged
            }
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
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val name = personDao.personById(personId)?.displayName
            if (name.isNullOrBlank()) { _mergeSuggestion.value = null; return@launch }
            // The Unsorted bucket is a mixed pile of junk, so its mean face is meaningless: never offer it
            // as a "might also be this person" merge, which would fold the whole pile into a named person.
            val otherId = personDao.otherPersonId(account)
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
                    if (pid == otherId) continue
                    // Never re-offer a cluster the user already said is not this person.
                    if (faceIdsByPerson[pid]?.any { it in rejectedFaceIds } == true) continue
                    val s = cosineSimilarity(target, c)
                    if (s > bestSim) { bestSim = s; chosen = pid }
                }
                chosen
            }
            if (bestId < 0L) { _mergeSuggestion.value = null; return@launch }
            _mergeSuggestion.value = observePeopleUseCase(
                userId,
                if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
            ).first().firstOrNull { it.personId == bestId }?.toPersonUi()
        }
    }

    /** Accept the current merge suggestion: fold [candidateId] into this named person, then look for the
     *  next candidate. This person stays; the candidate is absorbed. */
    fun acceptMergeSuggestion(personId: Long, candidateId: Long) {
        val name = _uiState.value.personName?.trim().orEmpty()
        if (name.isEmpty()) return
        // Keep the current card in place: the reload below swaps it straight to the next candidate, so the
        // banner never collapses to empty and back, which is what jerked the grid under it.
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            foldPersonInto(account, candidateId, name)
            faceIndexingScheduler.requestRecluster(userId)
            loadMergeSuggestion(personId)
        }
    }

    /** Dismiss the current merge suggestion for good: record the candidate's faces as "not this person"
     *  (name + faceId, so it survives a rebuild and is never offered or pulled in again), then look for
     *  the next candidate. */
    fun dismissMergeSuggestion(personId: Long, candidateId: Long) {
        val name = _uiState.value.personName?.trim().orEmpty()
        if (name.isEmpty()) return
        // As with accept, leave the current card up until the reload swaps in the next candidate, so the
        // list under the banner does not jump as it disappears and reappears.
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
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

    /** Rename the person, clearing the name back to null on empty input. Moves any manual memberships
     *  onto the new name so added photos follow the rename, then re-observes under it. */
    fun rename(personId: Long, newName: String, onMergedAway: () -> Unit = {}) {
        val trimmed = newName.trim().ifEmpty { null }
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            // Renaming onto a name another person already holds is a merge, not a duplicate: fold this
            // person into that one (same name = same person) so a rebuild cannot silently collapse two
            // same-named rows and lose the smaller. The caller navigates away, since this person is gone.
            if (trimmed != null && personDao.namedPeopleForUser(account)
                    .any { it.displayName == trimmed && it.id != personId }
            ) {
                foldPersonInto(account, personId, trimmed)
                // The fresh name attracts more of that person's faces than the fold moved; re-cluster now
                // so they join without waiting for a drained pass that may never come.
                faceIndexingScheduler.requestRecluster(userId)
                withContext(Dispatchers.Main) { onMergedAway() }
                return@launch
            }
            val old = personDao.personById(personId)?.displayName
            personDao.updateName(personId, trimmed)
            if (!old.isNullOrBlank() && !trimmed.isNullOrBlank() && old != trimmed) {
                personManualPhotoDao.rename(account, old, trimmed)
                faceDao.renameManualName(account, old, trimmed)
                notPersonDao.rename(account, old, trimmed)
                personCoverDao.rename(account, old, trimmed)
            }
            // Anchor the person's current faces to the name as confirmations (like a fold does), so a
            // later rebuild that splits the person keeps every face together instead of orphaning the
            // smaller shard. Setting the display name alone would leave the person held together
            // by the auto-grouping alone. Clearing on un-name stops the old name resurrecting.
            val faceIds = faceDao.faceIdsForPerson(account, personId)
            if (faceIds.isNotEmpty()) {
                if (trimmed != null) faceDao.labelFacesByIds(faceIds, trimmed)
                else faceDao.clearManualNameByIds(faceIds)
            }
            // Naming anchors these faces; re-cluster now so the name pulls in its other faces instead of
            // waiting for the next drained pass. Forced, so it runs even while indexing is paused.
            if (trimmed != null) faceIndexingScheduler.requestRecluster(userId)
            _uiState.update { it.copy(personName = trimmed) }
            load(personId)
            // Surface the merge prompt right after naming, so a freshly named cluster offers its
            // look-alikes immediately instead of only after the user leaves and reopens the person.
            if (trimmed != null) loadMergeSuggestion(personId) else clearMergeSuggestion()
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
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val name = personDao.personById(personId)?.displayName
            // "Not this person", not "delete": record the exact faces as rejected for this name (so they
            // never rejoin), then unassign them WITHOUT rejecting, so they recluster and can be
            // suggested for the right person on the next rebuild rather than vanishing from People.
            val faceIds = faceDao.faceIdsForPersonInPhotos(account, personId, photoKeys)
            if (!name.isNullOrBlank() && faceIds.isNotEmpty()) {
                notPersonDao.add(faceIds.map { NotPersonEntity(account, name, it) })
            }
            faceDao.unassignFaces(faceIds)
            if (!name.isNullOrBlank()) personManualPhotoDao.remove(account, name, photoKeys)
            val manual = if (name.isNullOrBlank()) emptyList()
                else personManualPhotoDao.photoKeysForNameList(account, name)
            val count = personPhotoCount(faceDao.distinctPhotoKeysForPerson(account, personId), manual)
            val cover = resolveCover(account, personId, name)
            personDao.updateCoverAndCount(personId, cover, count)
            _message.value = R.string.person_msg_removed
        }
    }

    /**
     * Mark the selected Unsorted faces as "not a person": the faces in those photos are rejected, so
     * they leave the bucket now and are skipped by every future clustering pass (the face-level analog
     * of the review screen's whole-cluster dismissal). Guest-safe, the account resolving to the local
     * sentinel with no Proton account. The cached cover + count are refreshed so the People list stays
     * honest; the grid drops the faces on its own, observing the same kept-faces flow removePhotos does.
     */
    fun markSelectedNotPerson(personId: Long, photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val name = personDao.personById(personId)?.displayName
            faceDao.rejectFacesForPersonInPhotos(account, personId, photoKeys)
            refreshPersonCoverAndCount(account, personId, name)
            _message.value = R.string.person_msg_dismissed
        }
    }

    /** Move the given selected photos' faces from this person onto the person named [rawName] (an existing
     *  one of that name, else a new person), confirming them there so the assignment survives the next
     *  rebuild. Picking an existing name reassigns; a brand-new name splits the faces into a new person. */
    fun moveSelectedToPerson(fromPersonId: Long, photoKeys: Collection<String>, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty() || photoKeys.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val faceIds = faceDao.faceIdsForPersonInPhotos(account, fromPersonId, photoKeys)
            if (faceIds.isEmpty()) return@launch
            val target = personDao.namedPeopleForUser(account)
                .firstOrNull { it.displayName == name && it.id != fromPersonId }?.id
                ?: personDao.insert(PersonEntity(userId = account, displayName = name))
            faceDao.reassignFacesToPerson(faceIds, target)   // move immediately
            faceDao.labelFacesByIds(faceIds, name)            // confirm -> anchors across a rebuild
            // Refresh both people's cached cover + count (same union pattern removePhotos uses).
            refreshPersonCoverAndCount(account, fromPersonId, personDao.personById(fromPersonId)?.displayName)
            refreshPersonCoverAndCount(account, target, name)
            personDao.deleteEmpty(account)
            _message.value = R.string.person_msg_moved
        }
    }

    private suspend fun refreshPersonCoverAndCount(account: String, personId: Long, name: String?) {
        val manual = if (name.isNullOrBlank()) emptyList()
            else personManualPhotoDao.photoKeysForNameList(account, name)
        val count = personPhotoCount(faceDao.distinctPhotoKeysForPerson(account, personId), manual)
        val cover = resolveCover(account, personId, name)
        personDao.updateCoverAndCount(personId, cover, count)
    }

    /** "This is not a person": reject every face in the cluster so it never groups again, drop the
     *  name-keyed curation, and remove the person. For dismissing a junk or group-photo cluster.
     *  [onDone] runs on the main thread so the screen can pop back. */
    fun ignorePerson(personId: Long, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val name = personDao.personById(personId)?.displayName
            // Capture the cluster's own faces before rejecting them, so their mean direction can also
            // sweep the same thing out of any other cluster it landed in (a statue seen across many
            // photos), rather than leaving the user to dismiss each look-alike cluster in turn.
            val reference = faceDao.facesForPerson(account, personId).map { unpackEmbedding(it.embedding) }
            faceDao.rejectAllForPerson(account, personId)
            hideSimilarFaces(account, reference)
            if (!name.isNullOrBlank()) {
                personManualPhotoDao.clearForName(account, name)
                notPersonDao.clearForName(account, name)
                personCoverDao.clearForName(account, name)
            }
            personDao.updateCoverAndCount(personId, null, 0)
            personDao.deleteEmpty(account)
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
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val name = personDao.personById(personId)?.displayName
            if (name.isNullOrBlank()) return@launch
            personManualPhotoDao.add(photoKeys.map { PersonManualPhotoEntity(account, name, it) })
            // Refresh the cached count so the People tile matches the grid (which observes the manual
            // keys live); otherwise the tile stays stale until the next clustering rebuild.
            val manual = personManualPhotoDao.photoKeysForNameList(account, name)
            val count = personPhotoCount(faceDao.distinctPhotoKeysForPerson(account, personId), manual)
            val cover = resolveCover(account, personId, name)
            personDao.updateCoverAndCount(personId, cover, count)
            _message.value = R.string.person_msg_added
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
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
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
