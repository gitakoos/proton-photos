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

package eu.akoos.photos.data.mlsync

import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.FaceCurationTimeEntity
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PersonNameChangeEntity
import eu.akoos.photos.data.db.entity.PersonNameStateEntity
import eu.akoos.photos.data.face.FaceReattachData
import eu.akoos.photos.data.face.ReattachBox
import eu.akoos.photos.domain.usecase.ReattachFace
import eu.akoos.photos.domain.usecase.ReattachLabel
import eu.akoos.photos.domain.usecase.isPortableFaceKey
import java.io.InputStream

/** Photos a Merge matches against this device's faces per batch. */
private const val MATCH_BATCH_PHOTOS = 200

/** Rows per database write while an import streams. */
private const val WRITE_BATCH = 500

/** What a read-only first pass learns of a face snapshot before anything is written. */
internal class FaceSnapshotSurvey(
    val header: MlSnapshotHeader.Faces,
    /** The faces a NOT_PERSON record names, whose placement an import keeps until those records arrive. */
    val notPersonFaceIds: Set<String>,
    /** Whether every photo's faces come together, so a Merge can match them a batch of photos at a time. */
    val facesGroupedByPhoto: Boolean,
)

/**
 * Reads a plaintext snapshot through to its end marker, so a malformed or cut-short one is refused before
 * the database is touched, and notes what the apply pass needs ahead of time. Null for a snapshot that is
 * not of kind FACES; a malformed one throws [MlSnapshotFormatException]. [input] is not closed.
 */
internal fun surveyFaceSnapshot(input: InputStream): FaceSnapshotSurvey? {
    val reader = MlSnapshotReader(input)
    val header = reader.header as? MlSnapshotHeader.Faces ?: return null
    val notPersonFaceIds = HashSet<String>()
    val finishedPhotos = HashSet<String>()
    var currentPhoto: String? = null
    var grouped = true
    for (record in reader) {
        when (record) {
            is MlSnapshotRecord.Face -> if (record.photoKey != currentPhoto) {
                currentPhoto?.let(finishedPhotos::add)
                if (record.photoKey in finishedPhotos) grouped = false
                currentPhoto = record.photoKey
            }
            is MlSnapshotRecord.NotPerson -> notPersonFaceIds += record.faceId
            else -> Unit
        }
    }
    return FaceSnapshotSurvey(header, notPersonFaceIds, grouped)
}

/** What an applied snapshot did, and the names it left for the reattach path. */
internal class FaceSnapshotApplied(
    /** Face rows written from the snapshot. */
    val facesWritten: Int,
    /** Faces this device already held that a Merge confirmed or removed. */
    val facesUpdated: Int,
    /** Distinct names the snapshot's faces carry. */
    val people: Int,
    /** Scan markers taken from the snapshot. */
    val scanMarkers: Int,
    /** Names to put back on this device's faces once they are detected, for a names-only snapshot. */
    val staged: FaceReattachData,
)

/**
 * Applies one snapshot to [account]'s face data as its records stream past, holding back nothing larger
 * than a batch of rows. The caller runs [run] inside a database transaction, so every write becomes
 * visible together once the reader has reached the end marker, and a failure anywhere leaves the database
 * as it was.
 *
 * A [FaceSnapshotFit.Full] snapshot writes rows. On [FaceSnapshotMode.Replace] the cloud photos' faces,
 * markers and curation are cleared first and the snapshot's take their place, the named people rebuilt
 * from its names; on [FaceSnapshotMode.Merge] rows are added only for the photos this device has not
 * scanned, and on the photos it has the snapshot's names and curation land through [mergeFaceCuration] on
 * the local face paired by [matchSnapshotFaces]. Its scan markers always import, so a face-free photo is
 * not scanned again here either.
 *
 * A [FaceSnapshotFit.NamesOnly] snapshot writes no face row and no marker, since its vectors do not
 * compare here and every photo must be detected by this device's own model. Its names, "not this person"
 * marks and removals are staged by where each face sat, for the reattach path to put back once the
 * library is detected; on Merge the photos already scanned here take theirs at once, by the same pairing
 * and the same local-wins rule. Manual photos and covers are plain name-keyed rows in either fit.
 *
 * The snapshot's renames and merges ([MlSnapshotRecord.NameChange]) come before its faces. On Merge each one
 * made after the name this device holds is carried out here, a rename or a fold into the person already
 * called the new name, so the faces that follow meet the people under their new names; one this device holds
 * a later name for is passed over. Every change and naming time is kept here too, so the next copy this
 * device writes hands them on.
 *
 * Attached photos, "not this person" marks and covers carry the time each was set by hand, and the pieces taken
 * away by hand come as [MlSnapshotRecord.Removal]s before the faces. Of a setting and a removal of the same piece,
 * here or there, the later wins, and of two covers the later; the times are kept here as well.
 */
internal class FaceSnapshotApplier(
    db: AppDatabase,
    private val account: String,
    private val mode: FaceSnapshotMode,
    private val fit: FaceSnapshotFit,
    private val survey: FaceSnapshotSurvey,
) {
    private val faceDao = db.faceDao()
    private val faceScanDao = db.faceScanDao()
    private val personDao = db.personDao()
    private val manualPhotoDao = db.personManualPhotoDao()
    private val notPersonDao = db.notPersonDao()
    private val coverDao = db.personCoverDao()
    private val clusterSummaryDao = db.clusterSummaryDao()
    private val nameDao = db.personNameDao()
    private val namedAt = HashMap<String, Long>()
    private val curationDao = db.faceCurationTimeDao()
    private val curation = HashMap<CurationKey, FaceCurationTimeEntity>()
    private val curationNotes = ArrayList<FaceCurationTimeEntity>()

    private val merge = mode == FaceSnapshotMode.Merge
    private val full = fit == FaceSnapshotFit.Full

    private val personIdByName = HashMap<String, Long>()
    private val nameByPersonId = HashMap<Long, String>()
    private val localCoverNames = HashSet<String>()
    private val localBlockedNames = HashMap<String, MutableSet<String>>()

    private val pendingKeys = ArrayList<String>()
    private val scannedHere = HashSet<String>()
    private val markedHere = HashSet<String>()

    private val waiting = LinkedHashMap<String, MutableList<WaitingFace>>()
    private val notPersonMatches = HashMap<String, LocalMatch>()

    private val faceRows = ArrayList<FaceEntity>()
    private val manualRows = ArrayList<PersonManualPhotoEntity>()
    private val notPersonRows = ArrayList<NotPersonEntity>()
    private val newMarkers = ArrayList<FaceScanEntity>()
    private val raisedMarkers = ArrayList<String>()
    private val photosWithoutMarker = HashSet<String>()
    private val touchedPeople = HashSet<Long>()

    private val stagedMembers = ArrayList<ReattachLabel>()
    private val stagedNots = ArrayList<ReattachLabel>()
    private val stagedRejected = ArrayList<ReattachBox>()
    private val stagedBoxes = HashMap<String, ReattachFace>()

    private val peopleNames = HashSet<String>()
    private var facesWritten = 0
    private var facesUpdated = 0
    private var scanMarkers = 0
    private var section = Section.KEYS

    /** Streams [reader]'s records into the database; see the class comment for what each one does. */
    suspend fun run(reader: MlSnapshotReader): FaceSnapshotApplied {
        prepare()
        for (record in reader) {
            enter(sectionOf(record))
            when (record) {
                is MlSnapshotRecord.PhotoKey -> onKey(record.photoKey)
                is MlSnapshotRecord.Person -> onPerson(record)
                is MlSnapshotRecord.NameChange -> onNameChange(record)
                is MlSnapshotRecord.Removal -> onRemoval(record)
                is MlSnapshotRecord.Face -> onFace(record)
                is MlSnapshotRecord.ManualPhoto -> onManualPhoto(record)
                is MlSnapshotRecord.NotPerson -> onNotPerson(record)
                is MlSnapshotRecord.Cover -> onCover(record)
                is MlSnapshotRecord.ScanMarker -> onScanMarker(record)
                is MlSnapshotRecord.SearchEmbedding -> Unit
            }
        }
        enter(Section.END)
        if (full) finishRows()
        if (curationNotes.isNotEmpty()) curationDao.set(curationNotes)
        return FaceSnapshotApplied(
            facesWritten = facesWritten,
            facesUpdated = facesUpdated,
            people = peopleNames.size,
            scanMarkers = scanMarkers,
            staged = FaceReattachData(stagedMembers, emptyList(), stagedNots, stagedRejected),
        )
    }

    private suspend fun prepare() {
        if (full && !merge) clearCloudFaceData()
        nameDao.statesForUser(account).associateTo(namedAt) { it.name to it.namedAt }
        for (row in curationDao.allForUser(account)) curation[CurationKey(row.kind, row.personName, row.ref)] = row
        if (!merge) return
        for (person in personDao.namedPeopleForUser(account)) {
            val name = person.displayName?.takeIf { it.isNotBlank() } ?: continue
            personIdByName[name] = person.id
            nameByPersonId[person.id] = name
        }
        coverDao.allForUser(account).mapTo(localCoverNames) { it.personName }
        for (mark in notPersonDao.allForUser(account)) {
            localBlockedNames.getOrPut(mark.faceId) { HashSet() }.add(mark.personName)
        }
    }

    /**
     * Clears what a Replace takes over: the faces, scan markers, manual photos, "not this person" marks and
     * covers of the cloud photos, and every grouping, which the snapshot's names and the next clustering
     * pass rebuild. Device-only photos keep their faces and curation, their confirmed names carrying their
     * people through that pass.
     */
    private suspend fun clearCloudFaceData() {
        faceDao.deleteByPhotoKeys(account, faceDao.facePhotoKeysForUser(account).filter(::isPortableFaceKey))
        faceScanDao.deleteByPhotoKeys(account, faceScanDao.scannedKeysForUser(account).filter(::isPortableFaceKey))
        manualPhotoDao.allForUser(account)
            .filter { isPortableFaceKey(it.photoKey) }
            .groupBy({ it.personName }, { it.photoKey })
            .forEach { (name, photoKeys) -> manualPhotoDao.remove(account, name, photoKeys) }
        for (mark in notPersonDao.allForUser(account)) {
            // A face id starts with its photo key, so it answers the same test.
            if (isPortableFaceKey(mark.faceId)) notPersonDao.deleteMark(account, mark.personName, mark.faceId)
        }
        for (cover in coverDao.allForUser(account)) {
            if (isPortableFaceKey(cover.photoKey)) coverDao.clearForName(account, cover.personName)
        }
        faceDao.clearAssignments(account)
        personDao.clearForUser(account)
        clusterSummaryDao.clearForUser(account)
    }

    /** Moves on to [next], closing out every section passed on the way. */
    private suspend fun enter(next: Section) {
        while (section < next) {
            when (section) {
                Section.KEYS -> resolvePendingKeys()
                Section.FACES -> {
                    flushFaceRows()
                    matchWaitingFaces()
                }
                Section.MANUAL_PHOTOS -> flushManualRows()
                Section.NOT_PERSONS -> flushNotPersonRows()
                Section.SCAN_MARKERS -> flushMarkers()
                else -> Unit
            }
            section = Section.entries[section.ordinal + 1]
        }
    }

    /** Keeps the later of the two times a name was given, here or on the writing device. */
    private suspend fun onPerson(person: MlSnapshotRecord.Person) {
        val name = snapshotName(person.name) ?: return
        if (person.namedAtMillis <= (namedAt[name] ?: 0L)) return
        namedAt[name] = person.namedAtMillis
        nameDao.setState(PersonNameStateEntity(account, name, person.namedAtMillis))
    }

    /**
     * Carries out a rename or merge made on the writing device ([nameChangeApplies]): the person named
     * [MlSnapshotRecord.NameChange.fromName] here is renamed, or folded into the person already holding the new
     * name. Kept here either way, so this device's next copy hands it on to the installs that did not see it.
     */
    private suspend fun onNameChange(change: MlSnapshotRecord.NameChange) {
        val from = snapshotName(change.fromName) ?: return
        if (change.toName.isEmpty()) {
            onNameRemoved(from, change.changedAtMillis)
            return
        }
        val to = snapshotName(change.toName) ?: return
        if (from == to) return
        val at = change.changedAtMillis
        val fromId = personIdByName[from]
        if (merge && fromId != null && nameChangeApplies(namedAt[from] ?: 0L, at)) renameHere(fromId, from, to)
        nameDao.addChange(PersonNameChangeEntity(account, from, to, at))
        if ((namedAt[from] ?: 0L) <= at && from !in personIdByName) {
            namedAt.remove(from)
            nameDao.deleteState(account, from)
        }
        if (at > (namedAt[to] ?: 0L)) {
            namedAt[to] = at
            nameDao.setState(PersonNameStateEntity(account, to, at))
        }
    }

    /** Takes the name [from] off the person holding it here, as a name taken off here does, when that came later
     *  than the name was given here; the person stays, unnamed. */
    private suspend fun onNameRemoved(from: String, at: Long) {
        val fromId = personIdByName[from]
        if (merge && fromId != null && nameChangeApplies(namedAt[from] ?: 0L, at)) {
            val faceIds = faceDao.faceIdsForPerson(account, fromId)
            personDao.updateName(fromId, null)
            if (faceIds.isNotEmpty()) faceDao.clearManualNameByIds(faceIds)
            personIdByName.remove(from)
            nameByPersonId.remove(fromId)
            touchedPeople += fromId
        }
        nameDao.addChange(PersonNameChangeEntity(account, from, "", at))
        if ((namedAt[from] ?: 0L) <= at && from !in personIdByName) {
            namedAt.remove(from)
            nameDao.deleteState(account, from)
        }
    }

    /**
     * Takes away a piece of curation removed on the writing device, when this device set it before that, and keeps
     * the removal so a setting of the same piece from before it is passed over here and handed on as removed.
     */
    private suspend fun onRemoval(removal: MlSnapshotRecord.Removal) {
        val name = snapshotName(removal.personName) ?: return
        val at = removal.removedAtMillis
        val ref = when (removal.kind) {
            MlSnapshotFormat.REMOVAL_MANUAL_PHOTO -> removal.photoKey.takeIf(::isPortableFaceKey) ?: return
            MlSnapshotFormat.REMOVAL_NOT_PERSON -> removal.faceId.takeIf { isPortableFaceKey(removal.photoKey) } ?: return
            else -> ""
        }
        val key = CurationKey(removal.kind, name, ref)
        val held = curation[key]
        if (!curationChangeApplies(held?.changedAt ?: 0L, at)) return
        if (held?.removed != true) {
            when (removal.kind) {
                MlSnapshotFormat.REMOVAL_MANUAL_PHOTO -> manualPhotoDao.remove(account, name, listOf(ref))
                MlSnapshotFormat.REMOVAL_NOT_PERSON -> {
                    notPersonDao.deleteMark(account, name, ref)
                    localBlockedNames[ref]?.remove(name)
                }
                MlSnapshotFormat.REMOVAL_COVER -> if (localCoverNames.remove(name)) coverDao.clearForName(account, name)
            }
        }
        note(key, removed = true, at)
    }

    /** Whether a setting of the piece [key] stamped [at] is taken: not when this device removed it later. */
    private fun settingTaken(key: CurationKey, at: Long): Boolean {
        val held = curation[key] ?: return true
        return !held.removed || curationChangeApplies(held.changedAt, at)
    }

    private fun note(key: CurationKey, removed: Boolean, at: Long) {
        val held = curation[key]
        if (held != null && held.changedAt >= at) return
        val row = FaceCurationTimeEntity(account, key.kind, key.name, key.ref, removed, at)
        curation[key] = row
        curationNotes += row
    }

    /** Renames the person [fromId], called [from], to [to], or folds them into the person already called [to],
     *  with the same bookkeeping as a rename or merge made here. */
    private suspend fun renameHere(fromId: Long, from: String, to: String) {
        val faceIds = faceDao.faceIdsForPerson(account, fromId)
        val toId = personIdByName[to]
        if (toId != null && toId != fromId) {
            if (faceIds.isNotEmpty()) faceDao.reassignFacesToPerson(faceIds, toId)
            personDao.updateCoverAndCount(fromId, null, 0)
            personDao.deleteEmpty(account)
            clusterSummaryDao.deleteByPerson(toId)
            clusterSummaryDao.deleteOrphansForUser(account)
            touchedPeople += toId
            touchedPeople -= fromId
        } else {
            personDao.updateName(fromId, to)
            personIdByName[to] = fromId
            nameByPersonId[fromId] = to
            touchedPeople += fromId
        }
        personIdByName.remove(from)
        if (nameByPersonId[fromId] == from) nameByPersonId.remove(fromId)
        if (faceIds.isNotEmpty()) faceDao.labelFacesByIds(faceIds, to)
        manualPhotoDao.rename(account, from, to)
        faceDao.renameManualName(account, from, to)
        notPersonDao.rename(account, from, to)
        coverDao.rename(account, from, to)
        curationDao.rename(account, from, to)
        for (key in curation.keys.filter { it.name == from }) {
            val row = curation.remove(key) ?: continue
            curation[key.copy(name = to)] = row.copy(personName = to)
        }
        if (localCoverNames.remove(from)) localCoverNames += to
        for (names in localBlockedNames.values) if (names.remove(from)) names += to
    }

    private suspend fun onKey(photoKey: String) {
        if (!merge) return
        pendingKeys += photoKey
        if (pendingKeys.size >= WRITE_BATCH) resolvePendingKeys()
    }

    /** Sorts the keys read since the last call into the photos this device has scanned (a marker, or faces
     *  written before a marker could be) and those it holds a marker for. */
    private suspend fun resolvePendingKeys() {
        if (pendingKeys.isEmpty()) return
        val marked = faceScanDao.scannedKeysAmong(account, pendingKeys).toHashSet()
        val withFaces = faceDao.facePhotoKeysIn(account, pendingKeys).toHashSet()
        for (photoKey in pendingKeys) {
            if (photoKey in marked) markedHere += photoKey
            if (photoKey in marked || photoKey in withFaces) scannedHere += photoKey
        }
        pendingKeys.clear()
    }

    private suspend fun onFace(face: MlSnapshotRecord.Face) {
        if (!isPortableFaceKey(face.photoKey)) return
        if (!face.rejected) (face.personName ?: face.confirmedName)?.let(peopleNames::add)
        when {
            !importsFaceRows(mode, face.photoKey in scannedHere) -> holdForMatch(face)
            full -> writeFaceRow(face)
            else -> stageFace(face)
        }
    }

    private suspend fun writeFaceRow(face: MlSnapshotRecord.Face) {
        val grouped = if (face.rejected) null else face.personName
        faceRows += FaceEntity(
            id = face.faceId,
            userId = account,
            photoKey = face.photoKey,
            left = face.left,
            top = face.top,
            right = face.right,
            bottom = face.bottom,
            landmarks = landmarkString(face.landmarks),
            embedding = face.embedding,
            personId = grouped?.let { personIdFor(it) },
            score = face.score,
            blur = face.blur,
            rejected = face.rejected,
            manualName = face.confirmedName,
        )
        photosWithoutMarker += face.photoKey
        facesWritten++
        if (faceRows.size >= WRITE_BATCH) flushFaceRows()
    }

    /** The person [name] is kept under here: this device's own named person of that name, or a new one. */
    private suspend fun personIdFor(name: String): Long {
        val id = personIdByName[name] ?: personDao.insert(PersonEntity(userId = account, displayName = name)).also {
            personIdByName[name] = it
            nameByPersonId[it] = name
        }
        touchedPeople += id
        return id
    }

    private fun stageFace(face: MlSnapshotRecord.Face) {
        if (face.faceId in survey.notPersonFaceIds) {
            stagedBoxes[face.faceId] = ReattachFace(face.faceId, face.photoKey, face.left, face.top, face.right, face.bottom)
        }
        if (face.rejected) {
            stagedRejected += ReattachBox(face.photoKey, face.left, face.top, face.right, face.bottom)
            return
        }
        // A confirmation outranks the automatic grouping, as it does when this device clusters.
        val name = face.confirmedName ?: face.personName ?: return
        stagedMembers += ReattachLabel(face.faceId, face.photoKey, face.left, face.top, face.right, face.bottom, name)
    }

    private suspend fun holdForMatch(face: MlSnapshotRecord.Face) {
        // A batch closes only between photos, so a photo's faces are always matched together.
        if (survey.facesGroupedByPhoto && face.photoKey !in waiting && waiting.size >= MATCH_BATCH_PHOTOS) {
            matchWaitingFaces()
        }
        waiting.getOrPut(face.photoKey) { ArrayList(2) }.add(
            WaitingFace(
                box = ReattachFace(face.faceId, face.photoKey, face.left, face.top, face.right, face.bottom),
                curation = SnapshotFaceCuration(face.personName, face.confirmedName, face.rejected),
            ),
        )
    }

    /** Pairs the held snapshot faces with this device's faces on the same photos and applies each pair's
     *  [mergeFaceCuration] change. A face removed here is not a candidate, so nothing reaches it. */
    private suspend fun matchWaitingFaces() {
        if (waiting.isEmpty()) return
        val photoKeys = ArrayList(waiting.keys)
        val held = waiting.values.flatten()
        waiting.clear()
        val local = faceDao.facesOnPhotos(account, photoKeys)
        val localById = local.associateBy { it.id }
        val confirmedHere = faceDao.manualNamesForIds(localById.keys).associate { it.id to it.manualName }
        val pairs = matchSnapshotFaces(
            snapshot = held.map { it.box },
            local = local.map { ReattachFace(it.id, it.photoKey, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom) },
        )
        val confirm = HashMap<String, MutableList<String>>()
        val remove = ArrayList<String>()
        for (face in held) {
            val localId = pairs[face.box.faceId] ?: continue
            val localFace = localById[localId] ?: continue
            val state = LocalFaceCuration(
                confirmedName = confirmedHere[localId],
                groupedName = localFace.personId?.let { nameByPersonId[it] },
                blockedNames = localBlockedNames[localId].orEmpty(),
            )
            val change = mergeFaceCuration(state, face.curation)
            when (change) {
                is FaceMergeChange.Confirm -> confirm.getOrPut(change.name) { ArrayList() }.add(localId)
                FaceMergeChange.Remove -> remove += localId
                FaceMergeChange.Keep -> Unit
            }
            if (face.box.faceId in survey.notPersonFaceIds) {
                val confirmedAfter = (change as? FaceMergeChange.Confirm)?.name ?: state.confirmedName
                notPersonMatches[face.box.faceId] = LocalMatch(localId, confirmedAfter)
            }
        }
        for ((name, ids) in confirm) faceDao.labelFacesByIds(ids, name)
        if (remove.isNotEmpty()) faceDao.rejectByIds(remove)
        facesUpdated += remove.size + confirm.values.sumOf { it.size }
    }

    private suspend fun onManualPhoto(record: MlSnapshotRecord.ManualPhoto) {
        if (!isPortableFaceKey(record.photoKey)) return
        val key = CurationKey(MlSnapshotFormat.REMOVAL_MANUAL_PHOTO, record.personName, record.photoKey)
        if (!settingTaken(key, record.addedAtMillis)) return
        manualRows += PersonManualPhotoEntity(account, record.personName, record.photoKey)
        if (record.addedAtMillis > 0L) note(key, removed = false, record.addedAtMillis)
        if (manualRows.size >= WRITE_BATCH) flushManualRows()
    }

    private suspend fun onNotPerson(record: MlSnapshotRecord.NotPerson) {
        if (!isPortableFaceKey(record.photoKey)) return
        if (!settingTaken(CurationKey(MlSnapshotFormat.REMOVAL_NOT_PERSON, record.personName, record.faceId), record.addedAtMillis)) return
        if (!importsFaceRows(mode, record.photoKey in scannedHere)) {
            // A photo scanned here: the mark follows its face onto the local face it was paired with.
            val match = notPersonMatches[record.faceId] ?: return
            val heldAt = curation[CurationKey(MlSnapshotFormat.REMOVAL_NOT_PERSON, record.personName, match.localFaceId)]?.changedAt ?: 0L
            val overrides = match.confirmedName == record.personName &&
                handMarkOverridesConfirmation(record.addedAtMillis, heldAt)
            if (notPersonMarkApplies(match.confirmedName, record.personName) || overrides) {
                addNotPersonRow(NotPersonEntity(account, record.personName, match.localFaceId))
                noteMark(record, match.localFaceId)
                localBlockedNames.getOrPut(match.localFaceId) { HashSet() }.add(record.personName)
            }
            if (overrides) {
                // Taken off the person by hand there after this device confirmed it: it leaves them here too,
                // free to regroup, as a photo taken off a person here does.
                faceDao.clearManualNameByIds(listOf(match.localFaceId))
                faceDao.unassignFaces(listOf(match.localFaceId))
                personIdByName[record.personName]?.let { touchedPeople += it }
            }
            return
        }
        if (full) {
            addNotPersonRow(NotPersonEntity(account, record.personName, record.faceId))
            noteMark(record, record.faceId)
        } else {
            val box = stagedBoxes[record.faceId] ?: return
            stagedNots += ReattachLabel(box.faceId, box.photoKey, box.left, box.top, box.right, box.bottom, record.personName)
        }
    }

    private fun noteMark(record: MlSnapshotRecord.NotPerson, faceId: String) {
        if (record.addedAtMillis > 0L) {
            note(CurationKey(MlSnapshotFormat.REMOVAL_NOT_PERSON, record.personName, faceId), removed = false, record.addedAtMillis)
        }
    }

    private suspend fun addNotPersonRow(row: NotPersonEntity) {
        notPersonRows += row
        if (notPersonRows.size >= WRITE_BATCH) flushNotPersonRows()
    }

    private suspend fun onCover(record: MlSnapshotRecord.Cover) {
        if (!isPortableFaceKey(record.photoKey)) return
        val key = CurationKey(MlSnapshotFormat.REMOVAL_COVER, record.personName, "")
        if (!settingTaken(key, record.setAtMillis)) return
        val heldAt = curation[key]?.takeUnless { it.removed }?.changedAt ?: 0L
        val laterThanHeld = curationChangeApplies(heldAt, record.setAtMillis)
        if (!coverApplies(mode, record.personName in localCoverNames) && !laterThanHeld) return
        coverDao.set(PersonCoverEntity(account, record.personName, record.photoKey))
        localCoverNames += record.personName
        if (record.setAtMillis > 0L) note(key, removed = false, record.setAtMillis)
    }

    private suspend fun onScanMarker(record: MlSnapshotRecord.ScanMarker) {
        // A names-only snapshot's photos have to be detected by this device's own model, so its markers stay out.
        if (!full || !isPortableFaceKey(record.photoKey)) return
        photosWithoutMarker -= record.photoKey
        when (val change = scanMarkerChange(record.photoKey in markedHere, record.hiResScanned)) {
            is ScanMarkerChange.Insert -> newMarkers += FaceScanEntity(account, record.photoKey, change.hiResScanned)
            ScanMarkerChange.RaiseHiRes -> raisedMarkers += record.photoKey
            ScanMarkerChange.Keep -> Unit
        }
        scanMarkers++
        if (newMarkers.size >= WRITE_BATCH || raisedMarkers.size >= WRITE_BATCH) flushMarkers()
    }

    /** The last writes of a full import, once every record is in. */
    private suspend fun finishRows() {
        // Faces that came without a marker would be scanned again here, and the fresh detection would write
        // over them and their names; mark them scanned, as the v3 import marks every photo it brings faces for.
        if (photosWithoutMarker.isNotEmpty()) {
            faceScanDao.upsert(photosWithoutMarker.map { FaceScanEntity(account, it) })
            photosWithoutMarker.clear()
        }
        // Every person the import put faces under gets its count and cover now, so People reads right before
        // the next clustering pass regroups; a Merge also drops the person's cached centroid, which no longer
        // covers those faces, so that pass rebuilds it.
        for (personId in touchedPeople) {
            val name = nameByPersonId[personId] ?: continue
            val manual = manualPhotoDao.photoKeysForNameList(account, name)
            val count = (faceDao.distinctPhotoKeysForPerson(account, personId) + manual).toHashSet().size
            val cover = coverDao.photoKeyForName(account, name)
                ?.let { faceDao.topFaceForPersonPhoto(account, personId, it) }
                ?: faceDao.topFaceForPerson(account, personId)
            personDao.updateCoverAndCount(personId, cover, count)
            if (merge) clusterSummaryDao.deleteByPerson(personId)
        }
    }

    private suspend fun flushFaceRows() {
        if (faceRows.isEmpty()) return
        faceDao.upsert(faceRows)
        faceRows.clear()
    }

    private suspend fun flushManualRows() {
        if (manualRows.isEmpty()) return
        manualPhotoDao.add(manualRows)
        manualRows.clear()
    }

    private suspend fun flushNotPersonRows() {
        if (notPersonRows.isEmpty()) return
        notPersonDao.add(notPersonRows)
        notPersonRows.clear()
    }

    private suspend fun flushMarkers() {
        if (newMarkers.isNotEmpty()) {
            faceScanDao.upsert(newMarkers)
            newMarkers.clear()
        }
        if (raisedMarkers.isNotEmpty()) {
            faceScanDao.markHiResScanned(account, raisedMarkers)
            raisedMarkers.clear()
        }
    }

    private fun sectionOf(record: MlSnapshotRecord): Section = when (record) {
        is MlSnapshotRecord.PhotoKey -> Section.KEYS
        is MlSnapshotRecord.Person -> Section.PEOPLE
        is MlSnapshotRecord.NameChange -> Section.NAME_CHANGES
        is MlSnapshotRecord.Removal -> Section.REMOVALS
        is MlSnapshotRecord.Face -> Section.FACES
        is MlSnapshotRecord.ManualPhoto -> Section.MANUAL_PHOTOS
        is MlSnapshotRecord.NotPerson -> Section.NOT_PERSONS
        is MlSnapshotRecord.Cover -> Section.COVERS
        is MlSnapshotRecord.ScanMarker -> Section.SCAN_MARKERS
        // The reader holds a FACES stream to its own sections, so no search record reaches here.
        is MlSnapshotRecord.SearchEmbedding -> throw MlSnapshotFormatException("a search record in a face snapshot")
    }

    /** The FACES sections in stream order, then the end. */
    private enum class Section { KEYS, PEOPLE, NAME_CHANGES, REMOVALS, FACES, MANUAL_PHOTOS, NOT_PERSONS, COVERS, SCAN_MARKERS, END }

    private data class CurationKey(val kind: Int, val name: String, val ref: String)

    /** A snapshot face on a photo this device has scanned, waiting to be paired with a local face. */
    private class WaitingFace(val box: ReattachFace, val curation: SnapshotFaceCuration)

    /** The local face a snapshot face was paired with, and the name confirmed for it once merged. */
    private class LocalMatch(val localFaceId: String, val confirmedName: String?)
}
