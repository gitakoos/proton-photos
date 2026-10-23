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

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.domain.usecase.AddPhotosToPersonUseCase
import eu.akoos.photos.domain.usecase.AssignFacesToPersonUseCase
import eu.akoos.photos.domain.usecase.AssignPersonNameUseCase
import eu.akoos.photos.domain.usecase.CentroidSum
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.PersonCentroids
import eu.akoos.photos.domain.usecase.cosineSimilarity
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.toPersonUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * Backs [NameOrMergeSheet]: the named people a nameless face or group can go to, and the ways it gets a
 * name. A face moves onto a picked or typed person; a group takes a new name as its own, or folds into a
 * person who already has one; a selection's faces move together onto a picked or typed person and its
 * photos without a face join that person too. Each action is written off the main thread and ends in one
 * [Outcome], tagged with the subject it was started on, so a sheet reacts only to its own.
 *
 * Works with no account: the people are read from the device's own partition and every write lands
 * there, and a later sign-in re-reads the list in place.
 */
@HiltViewModel
class NameOrMergeViewModel @Inject constructor(
    private val accountManager: AccountManager,
    observePeopleUseCase: ObservePeopleUseCase,
    getGalleryItems: GetGalleryItemsUseCase,
    private val assignFacesToPerson: AssignFacesToPersonUseCase,
    private val addPhotosToPerson: AddPhotosToPersonUseCase,
    private val assignPersonName: AssignPersonNameUseCase,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val personDao: PersonDao,
    private val faceDao: FaceDao,
    private val personCentroids: PersonCentroids,
) : ViewModel() {

    /** How an action ended. [subjectKey] names the subject it was started on, its face or its group, and
     *  [message] is the short line to show. */
    sealed interface Outcome {
        val subjectKey: String
        val message: Int

        /** The subject now belongs to [personId]. */
        data class Done(
            override val subjectKey: String,
            val personId: Long,
            @StringRes override val message: Int,
        ) : Outcome

        /** Nothing was changed. */
        data class Failed(
            override val subjectKey: String,
            @StringRes override val message: Int,
        ) : Outcome
    }

    /** What the sheet is open on; a group is also left out of its own list. */
    private val subject = MutableStateFlow<NameOrMergeSubject?>(null)

    /** The session's account and its named people, most photographed first, without the Unsorted bucket
     *  or the group being named. The session is the outer flow, so a sign-in or a sign-out re-reads it. */
    private val pickable = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            observePeopleUseCase(
                userId,
                if (userId == null) getGalleryItems.invokeLocalOnly() else getGalleryItems.invoke(userId),
                namedOnly = true,
            ).map { summaries -> (userId?.id ?: PhotoLocationEntity.LOCAL_USER) to summaries.mapNotNull { it.toPersonUi() } }
        }
        .combine(subject) { (account, list), subject ->
            Pickable(account, subject, NameOrMergeRules.pickablePeople(list, (subject as? NameOrMergeSubject.Group)?.personId))
        }

    /** How alike each pickable person is to the subject, read again only when the subject or the set of
     *  people changes, not on every refresh of the list while a scan runs. Empty until the first read, so
     *  the list shows at once in its own order. */
    private val likeness = pickable
        .map { Triple(it.account, it.subject, it.people.mapTo(HashSet()) { person -> person.personId }) }
        .distinctUntilChanged()
        .mapLatest { (account, subject, personIds) -> likenessTo(account, subject, personIds) }
        .onStart { emit(emptyMap()) }

    /** The named people to choose from, the most alike to the subject first and the rest after them, most
     *  photographed first. */
    val people: StateFlow<List<PersonUi>> = pickable
        .combine(likeness) { current, scores -> NameOrMergeRules.mostAlikeFirst(current.people, scores) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private data class Pickable(val account: String, val subject: NameOrMergeSubject?, val people: List<PersonUi>)

    /**
     * The cosine of each of [personIds]' mean faces to [subject]'s: the face itself, the mean of a
     * selection's chosen faces, or the group's own mean face. Empty when the subject has no readable face,
     * which leaves the list in its own order.
     */
    private suspend fun likenessTo(account: String, subject: NameOrMergeSubject?, personIds: Set<Long>): Map<Long, Float> =
        try {
            val groupId = (subject as? NameOrMergeSubject.Group)?.personId
            val centroids = personCentroids.load(account, exactFor = groupId) { it in personIds || it == groupId }
            val target = when (subject) {
                is NameOrMergeSubject.Group -> centroids[subject.personId]
                is NameOrMergeSubject.Face -> meanFace(account, listOf(subject.faceId))
                is NameOrMergeSubject.Faces -> meanFace(account, subject.faces.map { it.faceId })
                null -> null
            }
            if (target == null) emptyMap()
            else personIds.mapNotNull { id -> centroids[id]?.let { id to cosineSimilarity(target, it) } }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "people left unranked: ${e.javaClass.simpleName}")
            emptyMap()
        }

    /** The normalised mean of [faceIds]' vectors, or null when none is readable. */
    private suspend fun meanFace(account: String, faceIds: List<String>): FloatArray? {
        if (faceIds.isEmpty()) return null
        val sum = CentroidSum()
        faceIds.chunked(FACE_ID_CHUNK).forEach { chunk ->
            faceDao.vectorsByIdsChunk(account, chunk).forEach { sum.add(it.embedding) }
        }
        return sum.centroid()
    }

    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 1)

    /** One event per finished action, for the sheet that is open while it finishes. */
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    private val _working = MutableStateFlow(false)

    /** True while an action is being written; another one is ignored until it ends. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** Points the list at [subject], leaving a group out of its own choices. */
    fun bind(subject: NameOrMergeSubject) {
        this.subject.value = subject
    }

    /**
     * Moves the face onto [target]: a person picked by id, or a typed name, which is the person already
     * called that or a new one. Easily undone, so it needs no confirm. The use case asks for the regroup
     * that lets the name pull in the person's other faces.
     */
    fun assignFace(subject: NameOrMergeSubject.Face, target: AssignFacesToPersonUseCase.Target) =
        launchAction(subject) {
            val userId = accountManager.getPrimaryUserId().first()
            val personId = assignFacesToPerson(userId, listOf(subject.faceId), target)
            if (personId < 0) failed(subject)
            else Outcome.Done(subject.key, personId, R.string.person_msg_added)
        }

    /**
     * Adds a selection to [target]: its chosen faces move onto the person in one assignment, confirmed
     * under the name, and its photos without a face join the person the way a plain add attaches them;
     * one regroup follows when a face moved. A picked person still takes every photo when no face could
     * be written, so none of the selection is dropped; a typed name needs a written face, since a person
     * with no face shows up nowhere.
     */
    fun assignFaces(subject: NameOrMergeSubject.Faces, target: AssignFacesToPersonUseCase.Target) =
        launchAction(subject) {
            val userId = accountManager.getPrimaryUserId().first()
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val faceIds = subject.faces.map { it.faceId }
            val assigned = if (faceIds.isEmpty()) -1L
                else assignFacesToPerson(userId, faceIds, target, recluster = false)
            val facesAssigned = assigned >= 0
            try {
                val personId = NameOrMergeRules.facesPerson(assigned, target)
                    ?: return@launchAction failed(subject)
                if (!facesAssigned && !takesPhotos(account, personId)) return@launchAction failed(subject)
                addPhotosToPerson(personId, NameOrMergeRules.manualPhotoKeys(subject, facesAssigned))
                Outcome.Done(subject.key, personId, R.string.person_msg_added)
            } finally {
                if (facesAssigned) faceIndexingScheduler.requestRecluster(userId)
            }
        }

    /** Whether [personId] is a live named person of [account], the only kind a plain add attaches to. */
    private suspend fun takesPhotos(account: String, personId: Long): Boolean {
        val person = personDao.personById(personId) ?: return false
        return person.userId == account && !person.isOther && !person.displayName.isNullOrBlank()
    }

    /** Gives the group [name]. A name nobody has renames it; one a person already holds folds the group
     *  into that person, the same rule the person page's rename follows. */
    fun nameGroup(subject: NameOrMergeSubject.Group, name: String) = foldGroup(subject, name)

    /** Folds the group into [into]: its faces join that person under their name for good, and the emptied
     *  group is removed. The sheet confirms first, since this cannot be split back apart automatically. */
    fun mergeGroup(subject: NameOrMergeSubject.Group, into: PersonUi) {
        val name = into.displayName?.takeIf { it.isNotBlank() } ?: return
        foldGroup(subject, name)
    }

    /** The person page's rename and merge, applied to the group: the fold, then a regroup so the name
     *  pulls in faces still sitting elsewhere, then the page's own "Renamed" or "People merged". */
    private fun foldGroup(subject: NameOrMergeSubject.Group, name: String) = launchAction(subject) {
        val userId = accountManager.getPrimaryUserId().first()
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        // Only a live group of this account takes a name: never the Unsorted bucket, whose leftover faces
        // would all be confirmed under it, and never a group a regroup has since removed.
        val group = personDao.personById(subject.personId)
        if (group == null || group.userId != account || group.isOther) return@launchAction failed(subject)
        val personId = assignPersonName(account, subject.personId, name)
        if (personId < 0) {
            failed(subject)
        } else {
            faceIndexingScheduler.requestRecluster(userId)
            Outcome.Done(subject.key, personId, NameOrMergeRules.groupMessage(subject.personId, personId))
        }
    }

    /** Runs one action at a time off the main thread and reports how it ended; a throw is reported as a
     *  failure, so the sheet stays open with a message instead of the app going down. */
    private fun launchAction(subject: NameOrMergeSubject, action: suspend () -> Outcome) {
        if (!_working.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "name or merge failed: ${e.message}")
                    failed(subject)
                }
                _outcomes.emit(outcome)
            } finally {
                _working.value = false
            }
        }
    }

    private fun failed(subject: NameOrMergeSubject) = Outcome.Failed(subject.key, R.string.name_or_merge_failed)

    private companion object {
        const val TAG = "NameOrMergeVM"

        /** Face ids per vector read, well under the SQL host-variable limit. */
        const val FACE_ID_CHUNK = 500
    }
}

/** The subject's identity across recompositions, whatever image model it carries: its face, its group, or
 *  a selection's chosen faces and faceless photos. */
internal val NameOrMergeSubject.key: String
    get() = when (this) {
        is NameOrMergeSubject.Face -> "face:$faceId"
        is NameOrMergeSubject.Group -> "group:$personId"
        is NameOrMergeSubject.Faces ->
            "faces:" + faces.joinToString(",") { it.faceId } + "|" + manualPhotoKeys.joinToString(",")
    }

/** The sheet's list and message rules, kept free of Android so they are tested on the JVM. */
internal object NameOrMergeRules {

    /** The most places a stack of chosen faces takes in the header, a count included. */
    const val FACE_STACK_PLACES = 3

    /** The people a nameless face or group can go to: the named ones only, never the Unsorted bucket and
     *  never the group itself ([subjectPersonId]). The given order is kept. */
    fun pickablePeople(people: List<PersonUi>, subjectPersonId: Long?): List<PersonUi> =
        people.filter { !it.isOther && !it.displayName.isNullOrBlank() && it.personId != subjectPersonId }

    /** [people] with those scored in [likeness] first, the most alike leading, and everyone without a score
     *  after them in the order given. */
    fun mostAlikeFirst(people: List<PersonUi>, likeness: Map<Long, Float>): List<PersonUi> {
        if (likeness.isEmpty()) return people
        val (scored, rest) = people.partition { it.personId in likeness }
        return scored.sortedByDescending { likeness.getValue(it.personId) } + rest
    }

    /** What naming group [fromPersonId] reports, given the person it ended up as: a rename when it kept
     *  its own id, a merge when it folded into someone else. */
    @StringRes
    fun groupMessage(fromPersonId: Long, toPersonId: Long): Int =
        if (toPersonId == fromPersonId) R.string.person_msg_renamed else R.string.person_msg_merged

    /** Whether a typed new name can create a person for [subject]: always for one face or a group, and
     *  for a selection only while it holds a chosen face, since a person with no face shows up nowhere. */
    fun canCreate(subject: NameOrMergeSubject): Boolean =
        subject !is NameOrMergeSubject.Faces || subject.faces.isNotEmpty()

    /** The person a selection ends up on, given what the face assignment returned ([assignedPersonId],
     *  below zero when nothing was written): that person, else a picked person, who still takes the
     *  photos without a face; null for a typed name that wrote nothing, as there is no one to add to. */
    fun facesPerson(assignedPersonId: Long, target: AssignFacesToPersonUseCase.Target): Long? = when {
        assignedPersonId >= 0 -> assignedPersonId
        target is AssignFacesToPersonUseCase.Target.Existing -> target.personId
        else -> null
    }

    /** The photos a selection adds without a face: those that got none, and, when the faces were not
     *  written, the chosen faces' photos too, so no photo of the selection is dropped. */
    fun manualPhotoKeys(subject: NameOrMergeSubject.Faces, facesAssigned: Boolean): List<String> =
        if (facesAssigned) subject.manualPhotoKeys
        else (subject.faces.map { it.photoKey } + subject.manualPhotoKeys).distinct()

    /** How a stack of [count] chosen faces fills the header: the faces shown, and how many more a count
     *  in the last place stands for (zero when every face shows). */
    fun faceStack(count: Int): Pair<Int, Int> =
        if (count <= FACE_STACK_PLACES) count to 0
        else (FACE_STACK_PLACES - 1) to (count - (FACE_STACK_PLACES - 1))
}
