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

package eu.akoos.photos.presentation.people

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.gallery.FaceBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/** One face offered on a photo's row: its stable id, its box as fractions of the photo, and the name of
 *  the named person it is grouped under ([personName], null for any other face), shown under it so a face
 *  that is already someone's is not moved by accident. */
data class AddToPersonFaceOption(val faceId: String, val box: FaceBox, val personName: String? = null)

/**
 * One selected photo in the face step: the photo, the faces found on it clearest first, the face chosen
 * (none adds the photo without a face), and whether the row offers a face search of its own or is
 * running one.
 */
data class AddToPersonPhotoRow(
    val item: GalleryItem,
    val faces: List<AddToPersonFaceOption> = emptyList(),
    val chosenFaceId: String? = null,
    val canFindFaces: Boolean = false,
    val findingFaces: Boolean = false,
) {
    val photoKey: String get() = item.stableId

    /** The face chosen on this photo, or null when the photo goes in without one. */
    val chosenFace: AddToPersonFaceOption? get() = faces.firstOrNull { it.faceId == chosenFaceId }
}

/** One opening of the step: the selected photos. Compared by identity, so an opening only ever shows the
 *  rows read for it. */
class AddToPersonFaceRequest(val items: List<GalleryItem>)

data class AddToPersonFaceUiState(
    val request: AddToPersonFaceRequest? = null,
    val loading: Boolean = true,
    val rows: List<AddToPersonPhotoRow> = emptyList(),
)

/**
 * Backs the face step of Add to person: reads the faces on each selected photo and the named people
 * they belong to, starts a photo's face chosen only where that is safe ([AddToPersonFaces.preselect]),
 * and runs a face search on one photo when asked. The choice is handed to the name step as it stands, so
 * nothing is written here.
 *
 * Works the same with no account: every read is keyed to the account or, for a guest, the local
 * partition, and the face search takes the nullable user.
 */
@HiltViewModel
class AddToPersonFaceViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddToPersonFaceUiState())
    val uiState: StateFlow<AddToPersonFaceUiState> = _uiState.asStateFlow()

    /** What the current opening reads once and a face search on it reuses. */
    private class Session(
        val request: AddToPersonFaceRequest,
        val userId: UserId?,
        val account: String,
        val faceRecognitionOn: Boolean,
        val namedPeople: Map<Long, String>,
    )

    private var session: Session? = null

    /** Reads the rows for [request], off the main thread. Runs in the sheet's own scope, so closing the
     *  sheet stops it. A failed read still lists every photo, faceless, so the selection can be added
     *  without faces. */
    suspend fun load(request: AddToPersonFaceRequest) {
        session = null
        _uiState.value = AddToPersonFaceUiState(request = request)
        val loaded: Pair<Session?, List<AddToPersonPhotoRow>> = withContext(Dispatchers.Default) {
            val ordered = AddToPersonFaces.orderedItems(request.items)
            try {
                val opened = openSession(request)
                opened to rowsFor(opened, ordered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "face step read failed: ${e.message}")
                null to ordered.map { AddToPersonPhotoRow(it) }
            }
        }
        val (opened, rows) = loaded
        session = opened
        _uiState.value = AddToPersonFaceUiState(request = request, loading = false, rows = rows)
    }

    /** Chooses [faceId] on its photo's row, or clears it when it is the chosen one already. */
    fun toggle(photoKey: String, faceId: String) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.photoKey == photoKey) AddToPersonFaces.toggle(it, faceId) else it })
        }
    }

    /**
     * Looks for faces on one faceless photo now, at the sensitive setting the viewer's own face search
     * uses, then shows what it found under the same preselection rule. A search that finds nothing
     * leaves the row saying so, without the button.
     */
    fun findFaces(photoKey: String) {
        val s = session ?: return
        val state = _uiState.value
        if (state.request !== s.request) return
        val row = state.rows.firstOrNull { it.photoKey == photoKey } ?: return
        if (!row.canFindFaces || row.findingFaces) return
        updateRow(s, photoKey) { it.copy(findingFaces = true) }
        viewModelScope.launch {
            val faces: List<FaceOnPhoto> = withContext(Dispatchers.Default) {
                try {
                    if (!faceIndexingScheduler.indexPhotoOnDemand(row.item, s.userId, force = true)) {
                        emptyList<FaceOnPhoto>()
                    } else {
                        val onPhoto = faceDao.facesOnPhotos(s.account, listOf(photoKey))
                        AddToPersonFaces.facesByPhoto(onPhoto)[photoKey].orEmpty()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "face search failed: ${e.message}")
                    emptyList<FaceOnPhoto>()
                }
            }
            updateRow(s, photoKey) { current ->
                if (faces.isEmpty()) current.copy(findingFaces = false, canFindFaces = false)
                else rowFor(s, current.item, faces, hasAnyFaceRow = true)
            }
        }
    }

    private suspend fun openSession(request: AddToPersonFaceRequest): Session {
        val userId = accountManager.getPrimaryUserId().first()
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val prefs = context.settingsDataStore.data.first()
        val faceOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        val named = if (faceOn) AddToPersonFaces.namedPeople(personDao.namedPeopleForUser(account)) else emptyMap()
        return Session(request, userId, account, faceOn, named)
    }

    private suspend fun rowsFor(s: Session, ordered: List<GalleryItem>): List<AddToPersonPhotoRow> {
        // With face recognition off no face data is read, so every photo goes in without a face.
        if (!s.faceRecognitionOn) return ordered.map { AddToPersonPhotoRow(it) }
        val keys = ordered.map { it.stableId }
        val facesByPhoto = AddToPersonFaces.facesByPhoto(faceDao.facesOnPhotos(s.account, keys))
        // A photo whose only faces were removed from People lists none here, and is kept from a search
        // of its own, which would detect those faces again over the removal.
        val faceless = keys.filter { facesByPhoto[it].isNullOrEmpty() }
        val anyFaceRow: Set<String> =
            if (faceless.isEmpty()) emptySet() else faceDao.facePhotoKeysIn(s.account, faceless).toHashSet()
        return ordered.map { item ->
            val key = item.stableId
            rowFor(s, item, facesByPhoto[key].orEmpty(), hasAnyFaceRow = key in anyFaceRow)
        }
    }

    private fun rowFor(
        s: Session,
        item: GalleryItem,
        faces: List<FaceOnPhoto>,
        hasAnyFaceRow: Boolean,
    ): AddToPersonPhotoRow {
        val options = AddToPersonFaces.options(faces, s.namedPeople)
        return AddToPersonPhotoRow(
            item = item,
            faces = options,
            chosenFaceId = AddToPersonFaces.preselect(options),
            canFindFaces = AddToPersonFaces.canFindFaces(s.faceRecognitionOn, faces.isNotEmpty(), hasAnyFaceRow),
        )
    }

    private inline fun updateRow(
        s: Session,
        photoKey: String,
        crossinline transform: (AddToPersonPhotoRow) -> AddToPersonPhotoRow,
    ) {
        _uiState.update { state ->
            if (state.request !== s.request) state
            else state.copy(rows = state.rows.map { if (it.photoKey == photoKey) transform(it) else it })
        }
    }
}

/**
 * The rules of the face step as plain functions, so they are tested on the JVM: the row order, the
 * per-photo face lists and the names under them, which face starts chosen, the single-choice toggle, the
 * face search offer, when the step is skipped, and what the name step is handed.
 */
internal object AddToPersonFaces {

    private val clearestFirst = compareByDescending<FaceOnPhoto> { it.score }.thenBy { it.id }

    /** The selection in timeline order, newest first with the stable id as the tie-break, one row per
     *  photo. */
    fun orderedItems(items: Collection<GalleryItem>): List<GalleryItem> =
        items.distinctBy { it.stableId }
            .sortedWith(compareByDescending<GalleryItem> { it.captureTimeMs }.thenBy { it.stableId })

    /** [faces] grouped per photo, each photo's faces clearest first with the id as the tie-break. */
    fun facesByPhoto(faces: Collection<FaceOnPhoto>): Map<String, List<FaceOnPhoto>> =
        faces.groupBy { it.photoKey }.mapValues { (_, onPhoto) -> onPhoto.sortedWith(clearestFirst) }

    /** The named people by id, the names a face can show under it: never the Unsorted bucket, and never a
     *  blank name. */
    fun namedPeople(people: List<PersonEntity>): Map<Long, String> = buildMap {
        for (person in people) {
            val name = person.displayName?.takeIf { it.isNotBlank() } ?: continue
            if (!person.isOther) put(person.id, name)
        }
    }

    /** One photo's [faces] as the row offers them, each carrying the name of the named person it is
     *  grouped under; an ungrouped face, or one in an unnamed group or the Unsorted bucket, has none. */
    fun options(faces: List<FaceOnPhoto>, namedPeople: Map<Long, String>): List<AddToPersonFaceOption> =
        faces.map { face ->
            AddToPersonFaceOption(
                faceId = face.id,
                box = FaceBox(face.boxLeft, face.boxTop, face.boxRight, face.boxBottom),
                personName = face.personId?.let { namedPeople[it] },
            )
        }

    /**
     * The face on one photo that starts chosen, or null: only a photo's single face, and only while no
     * named person holds it. A face that is already someone's may be a bystander whose name must not move,
     * and in a photo of several faces there is no telling which one is meant, so both start unchosen.
     */
    fun preselect(faces: List<AddToPersonFaceOption>): String? =
        faces.singleOrNull()?.takeIf { it.personName == null }?.faceId

    /** Single choice per photo: a tap on the chosen face clears it, a tap on another chooses that one,
     *  and a face the row does not hold changes nothing. */
    fun toggle(row: AddToPersonPhotoRow, faceId: String): AddToPersonPhotoRow = when {
        row.faces.none { it.faceId == faceId } -> row
        row.chosenFaceId == faceId -> row.copy(chosenFaceId = null)
        else -> row.copy(chosenFaceId = faceId)
    }

    /** Whether a photo offers a face search of its own: only with face recognition on, and only for a
     *  photo holding no face row at all, so a search never detects over faces the user removed. */
    fun canFindFaces(faceRecognitionOn: Boolean, hasKeptFace: Boolean, hasAnyFaceRow: Boolean): Boolean =
        faceRecognitionOn && !hasKeptFace && !hasAnyFaceRow

    /** The selection's face when it is exactly one photo holding exactly one face that no named person
     *  holds, the case that opens straight on the name step with that face; null otherwise. A lone face
     *  someone already holds goes through the face step, which shows whose it is, so a bystander is not
     *  moved by accident. */
    fun loneFace(rows: List<AddToPersonPhotoRow>): AddToPersonFaceOption? =
        rows.singleOrNull()?.faces?.singleOrNull()?.takeIf { it.personName.isNullOrBlank() }

    /** The photos with no face chosen, in row order, which join the person without a face. */
    fun manualPhotoKeys(rows: List<AddToPersonPhotoRow>): List<String> =
        rows.filter { it.chosenFace == null }.map { it.photoKey }

    /**
     * What the name step is handed for [rows]: each chosen face with its photo and the image [sourceOf]
     * gives for that photo, in row order, and the photos with none chosen.
     */
    fun nameSubject(rows: List<AddToPersonPhotoRow>, sourceOf: (GalleryItem) -> Any?): NameOrMergeSubject.Faces =
        NameOrMergeSubject.Faces(
            faces = rows.mapNotNull { row ->
                val face = row.chosenFace ?: return@mapNotNull null
                ChosenFace(face.faceId, row.photoKey, sourceOf(row.item), face.box)
            },
            manualPhotoKeys = manualPhotoKeys(rows),
        )

    /** The name step's subject for a selection that skips the face step ([loneFace]): its one face, and
     *  no photo without one. Null when the step is not skipped. */
    fun loneFaceSubject(rows: List<AddToPersonPhotoRow>, sourceOf: (GalleryItem) -> Any?): NameOrMergeSubject.Faces? {
        val face = loneFace(rows) ?: return null
        return nameSubject(rows.map { it.copy(chosenFaceId = face.faceId) }, sourceOf)
    }
}

private const val TAG = "AddToPersonFaceVM"
