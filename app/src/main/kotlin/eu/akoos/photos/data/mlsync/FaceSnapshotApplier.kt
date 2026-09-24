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
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.FaceScanEntity
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
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
                is MlSnapshotRecord.Person -> Unit
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
        manualRows += PersonManualPhotoEntity(account, record.personName, record.photoKey)
        if (manualRows.size >= WRITE_BATCH) flushManualRows()
    }

    private suspend fun onNotPerson(record: MlSnapshotRecord.NotPerson) {
        if (!isPortableFaceKey(record.photoKey)) return
        if (!importsFaceRows(mode, record.photoKey in scannedHere)) {
            // A photo scanned here: the mark follows its face onto the local face it was paired with.
            val match = notPersonMatches[record.faceId] ?: return
            if (notPersonMarkApplies(match.confirmedName, record.personName)) {
                addNotPersonRow(NotPersonEntity(account, record.personName, match.localFaceId))
            }
            return
        }
        if (full) {
            addNotPersonRow(NotPersonEntity(account, record.personName, record.faceId))
        } else {
            val box = stagedBoxes[record.faceId] ?: return
            stagedNots += ReattachLabel(box.faceId, box.photoKey, box.left, box.top, box.right, box.bottom, record.personName)
        }
    }

    private suspend fun addNotPersonRow(row: NotPersonEntity) {
        notPersonRows += row
        if (notPersonRows.size >= WRITE_BATCH) flushNotPersonRows()
    }

    private suspend fun onCover(record: MlSnapshotRecord.Cover) {
        if (!isPortableFaceKey(record.photoKey)) return
        if (!coverApplies(mode, record.personName in localCoverNames)) return
        coverDao.set(PersonCoverEntity(account, record.personName, record.photoKey))
        localCoverNames += record.personName
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
        is MlSnapshotRecord.Face -> Section.FACES
        is MlSnapshotRecord.ManualPhoto -> Section.MANUAL_PHOTOS
        is MlSnapshotRecord.NotPerson -> Section.NOT_PERSONS
        is MlSnapshotRecord.Cover -> Section.COVERS
        is MlSnapshotRecord.ScanMarker -> Section.SCAN_MARKERS
        // The reader holds a FACES stream to its own sections, so no search record reaches here.
        is MlSnapshotRecord.SearchEmbedding -> throw MlSnapshotFormatException("a search record in a face snapshot")
    }

    /** The FACES sections in stream order, then the end. */
    private enum class Section { KEYS, PEOPLE, FACES, MANUAL_PHOTOS, NOT_PERSONS, COVERS, SCAN_MARKERS, END }

    /** A snapshot face on a photo this device has scanned, waiting to be paired with a local face. */
    private class WaitingFace(val box: ReattachFace, val curation: SnapshotFaceCuration)

    /** The local face a snapshot face was paired with, and the name confirmed for it once merged. */
    private class LocalMatch(val localFaceId: String, val confirmedName: String?)
}
