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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceHead
import eu.akoos.photos.data.db.entity.FaceEntity
import eu.akoos.photos.data.db.entity.PersonCoverEntity
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.usecase.FACE_CLUSTER_PARAMS_VERSION
import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import eu.akoos.photos.domain.usecase.FACE_MODEL_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject

/** Rows per page of the export's whole-row face read, each carrying its embedding. */
private const val FACE_PAGE = 256

/** Rows per page of the export's planning reads, which carry no embedding. */
private const val HEAD_PAGE = 2000

/** Passes an export makes before it gives up on a face index that keeps changing under it. */
private const val EXPORT_ATTEMPTS = 3

/** Planned faces a plan makes room for before it first grows. */
private const val PLANNED_FACES_INITIAL = 256

private const val FACE_EMBEDDING_BYTES = FACE_EMBEDDING_DIM * Float.SIZE_BYTES

/** What an export wrote: the counts a caller reports, and the plaintext size before sealing. */
data class FaceSnapshotExport(
    val faces: Int,
    val people: Int,
    val photos: Int,
    val scanMarkers: Int,
    val plaintextBytes: Long,
)

/** Who wrote a snapshot and with which pipeline, the header fields besides the counts. */
internal data class FaceSnapshotOrigin(
    val device: FaceSnapshotDevice,
    val faceModelVersion: Int,
    val faceModelSha256: String,
    val clusterParamsVersion: Int,
    val createdAtMillis: Long,
)

/** The face index changed under every one of an export's attempts, so nothing was sealed. */
class FaceIndexChangedException : IllegalStateException("the face index kept changing while it was read")

/**
 * Writes an account's face index as a sealed FACES snapshot ([MlSnapshotFormat] inside
 * [MlSnapshotEnvelope]), the file a Settings export saves and the Drive copy another device restores.
 *
 * It carries the faces on cloud photos only (the photos a Drive link id names, the v3 export's own test:
 * a device `content://` uri means nothing on another phone): each face's box, landmarks, scores, removed
 * bit, embedding, the named person it is grouped under and the name confirmed for it, plus the manual
 * photos, "not this person" marks and chosen covers of those photos, and the scan marker of every cloud
 * photo the indexer finished, a face-free one as much as any, so a restoring device never scans those
 * photos again. The header names the face pipeline and recognition model the embeddings belong to and
 * the clustering parameters the people were grouped under, as stored with the index here.
 *
 * Nothing is held whole in memory: the faces are read a page at a time and streamed out, and only the
 * photo keys and names the format's tables need are kept, plus a key reference and an index for each
 * planned face and a key reference for each planned scan marker. The plaintext goes to an app-private
 * scratch file, deleted once sealed.
 *
 * No transaction spans the two read passes, so the indexer and every other writer carry on while an export
 * runs. The header declares its counts before the first record, so the second pass writes exactly the rows
 * the first one planned and leaves out any added since. A planned face or marker that is gone by then, a
 * face whose person or confirmed name the planned name table does not hold, or a named person whose name
 * changed (a rename, merge or removal in between) abandons the pass, and the export starts over from a
 * fresh plan, up to [EXPORT_ATTEMPTS] times before it throws [FaceIndexChangedException].
 *
 * The app builds it from [AppDatabase]; a test hands in its own [FaceDao] to change the index between
 * the passes.
 */
class FaceSnapshotExporter internal constructor(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val sealing: FaceSnapshotSealing,
    private val faceDao: FaceDao,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase,
        sealing: FaceSnapshotSealing,
    ) : this(context, appDatabase, sealing, appDatabase.faceDao())

    /** Seals [userId]'s face snapshot into [output], which is flushed and left open. Blocking work runs
     *  on the IO dispatcher. An index that changes under every attempt throws [FaceIndexChangedException]
     *  before anything reaches [output]. */
    suspend fun export(userId: UserId, output: OutputStream): FaceSnapshotExport = withContext(Dispatchers.IO) {
        val prefs = context.settingsDataStore.data.first()
        val origin = FaceSnapshotOrigin(
            device = sealing.device(),
            // The generation the stored faces were indexed under, which differs from this build's while a
            // model migration is still pending; an importer then takes the names alone.
            faceModelVersion = prefs[SettingsKeys.FACE_MODEL_VERSION_KEY] ?: FACE_MODEL_VERSION,
            faceModelSha256 = FaceModelAssets.EMBED_MODEL.sha256,
            clusterParamsVersion = prefs[SettingsKeys.FACE_CLUSTER_PARAMS_VERSION_KEY] ?: FACE_CLUSTER_PARAMS_VERSION,
            createdAtMillis = System.currentTimeMillis(),
        )
        sealing.sealKeys(userId).use { keys ->
            val plaintext = FaceSnapshotScratch.newFile(context)
            try {
                val written = writeWithRetries(userId, plaintext, origin)
                FileInputStream(plaintext).use { MlSnapshotEnvelope.seal(it, output, sealing.crypto, keys) }
                output.flush()
                written.copy(plaintextBytes = plaintext.length())
            } finally {
                plaintext.delete()
            }
        }
    }

    /** Writes [userId]'s plaintext snapshot into [file], each attempt over what the one before it left. */
    private suspend fun writeWithRetries(userId: UserId, file: File, origin: FaceSnapshotOrigin): FaceSnapshotExport {
        repeat(EXPORT_ATTEMPTS) {
            BufferedOutputStream(FileOutputStream(file)).use { writePlaintext(userId, it, origin) }?.let { return it }
        }
        throw FaceIndexChangedException()
    }

    /**
     * One attempt: plans the snapshot of [userId]'s face index and streams it into [output], which is
     * flushed and left open. Null when the index changed between the passes in a way the plan cannot carry,
     * with [output] left holding an unfinished stream to discard. A null [userId] reads the guest partition.
     */
    internal suspend fun writePlaintext(
        userId: UserId?,
        output: OutputStream,
        origin: FaceSnapshotOrigin,
    ): FaceSnapshotExport? {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val plan = plan(account)
        val writer = MlSnapshotWriter(output, plan.header(origin))
        for (key in plan.keys) writer.write(MlSnapshotRecord.PhotoKey(key))
        for (name in plan.names) writer.write(MlSnapshotRecord.Person(name))
        var facesWritten = 0
        val groupedNames = HashSet<String>()
        forEachFace(account) { face ->
            val record = plan.faceRecord(face) ?: return@forEachFace
            // Detected since the plan, or met while a planned face before it is missing: left out either way.
            if (!plan.faces.isAt(facesWritten, record.photoKey, record.faceIndex)) return@forEachFace
            // A rename, merge or removal since the plan gave it a name the name table does not hold.
            if (!plan.holdsNames(record)) return null
            writer.write(record)
            record.personName?.let(groupedNames::add)
            facesWritten++
        }
        if (facesWritten < plan.faces.size) return null
        for (row in plan.manualPhotos) writer.write(MlSnapshotRecord.ManualPhoto(row.personName, row.photoKey))
        for (mark in plan.notPersons) writer.write(MlSnapshotRecord.NotPerson(mark.personName, mark.photoKey, mark.faceIndex))
        for (row in plan.covers) writer.write(MlSnapshotRecord.Cover(row.personName, row.photoKey))
        var markersWritten = 0
        forEachMarker(account) { photoKey, hiRes ->
            if (markersWritten < plan.markers.size && photoKey == plan.markers[markersWritten]) {
                writer.write(MlSnapshotRecord.ScanMarker(photoKey, hiRes))
                markersWritten++
            }
        }
        if (markersWritten < plan.markers.size) return null
        // A person renamed or named since the plan would leave its grouped faces under the plan's name.
        if (namedPeople(account) != plan.nameByPersonId) return null
        writer.finish()
        return FaceSnapshotExport(
            faces = facesWritten,
            people = groupedNames.size,
            photos = plan.keys.size,
            scanMarkers = markersWritten,
            plaintextBytes = 0L,
        )
    }

    /**
     * The first pass: every row the snapshot will hold is picked and counted without reading a single
     * embedding, and the photo keys and names the records use are gathered for the tables that lead the
     * stream. The second pass in [writePlaintext] applies the very same tests to the whole rows, and meets
     * the planned faces and markers in the same key order.
     */
    private suspend fun plan(account: String): ExportPlan {
        val nameByPersonId = namedPeople(account)
        val manualPhotos = appDatabase.personManualPhotoDao().allForUser(account)
            .filter { snapshotName(it.personName) != null && carriesPhotoKey(it.photoKey) }
        val covers = appDatabase.personCoverDao().allForUser(account)
            .filter { snapshotName(it.personName) != null && carriesPhotoKey(it.photoKey) }
        val marks = appDatabase.notPersonDao().allForUser(account).mapNotNull { row ->
            val name = snapshotName(row.personName) ?: return@mapNotNull null
            val (photoKey, faceIndex) = faceIdParts(row.faceId) ?: return@mapNotNull null
            if (carriesPhotoKey(photoKey)) ExportedMark(name, photoKey, faceIndex, row.faceId) else null
        }
        val markedFaceIds = marks.mapTo(HashSet()) { it.faceId }

        // Each key maps to its own string, so a planned face or marker refers to the one copy the table holds.
        val keys = HashMap<String, String>()
        fun addKey(photoKey: String): String = keys.getOrPut(photoKey) { photoKey }
        val names = HashSet<String>()
        val faces = PlannedFaces()
        val exportedMarkedFaces = HashSet<String>()
        forEachFaceHead(account) { head ->
            if (!exportsFace(head.id, head.photoKey, head.embeddingBytes)) return@forEachFaceHead
            val faceIndex = faceIndexIn(head.id, head.photoKey) ?: return@forEachFaceHead
            faces.add(addKey(head.photoKey), faceIndex)
            groupedName(head.personId, head.rejected, nameByPersonId)?.let(names::add)
            snapshotName(head.manualName)?.let(names::add)
            if (head.id in markedFaceIds) exportedMarkedFaces += head.id
        }
        // A mark travels with the face it names, the way the v3 export kept only the marks of the faces it
        // carried; a mark on a face no longer detected would only bind to whatever face takes its index.
        val notPersons = marks.filter { it.faceId in exportedMarkedFaces }
        for (row in manualPhotos) { addKey(row.photoKey); names += row.personName }
        for (mark in notPersons) { addKey(mark.photoKey); names += mark.personName }
        for (row in covers) { addKey(row.photoKey); names += row.personName }
        val markers = ArrayList<String>()
        forEachMarker(account) { photoKey, _ -> markers += addKey(photoKey) }
        return ExportPlan(
            nameByPersonId = nameByPersonId,
            keys = keys.keys,
            nameSet = names,
            faces = faces,
            manualPhotos = manualPhotos,
            notPersons = notPersons,
            covers = covers,
            markers = markers,
        )
    }

    /** Every named person's id with the name a snapshot carries for them, what a face's person resolves to. */
    private suspend fun namedPeople(account: String): Map<Long, String> {
        val nameByPersonId = HashMap<Long, String>()
        for (person in appDatabase.personDao().namedPeopleForUser(account)) {
            snapshotName(person.displayName)?.let { nameByPersonId[person.id] = it }
        }
        return nameByPersonId
    }

    private suspend inline fun forEachFaceHead(account: String, action: (FaceHead) -> Unit) {
        var after = ""
        while (true) {
            val page = faceDao.faceHeadsAfter(account, after, HEAD_PAGE)
            page.forEach(action)
            if (page.size < HEAD_PAGE) return
            after = page.last().id
        }
    }

    private suspend inline fun forEachFace(account: String, action: (FaceEntity) -> Unit) {
        var after = ""
        while (true) {
            val page = faceDao.facesAfter(account, after, FACE_PAGE)
            page.forEach(action)
            if (page.size < FACE_PAGE) return
            after = page.last().id
        }
    }

    /** Every scan marker the snapshot carries, in key order: those of the cloud photos. */
    private suspend inline fun forEachMarker(account: String, action: (String, Boolean) -> Unit) {
        var after = ""
        while (true) {
            val page = appDatabase.faceScanDao().markersAfter(account, after, HEAD_PAGE)
            for (marker in page) {
                if (carriesPhotoKey(marker.photoKey)) action(marker.photoKey, marker.hiResScanned)
            }
            if (page.size < HEAD_PAGE) return
            after = page.last().photoKey
        }
    }

    private class ExportedMark(val personName: String, val photoKey: String, val faceIndex: Int, val faceId: String)

    /**
     * The faces a plan picked, in the order the face walk meets them, each as its photo key (the key table's
     * own string) and its detection index, so the write pass tells a planned face from one detected since
     * without holding a face id apiece.
     */
    private class PlannedFaces {
        private var photoKeys = arrayOfNulls<String>(PLANNED_FACES_INITIAL)
        private var indexes = IntArray(PLANNED_FACES_INITIAL)

        var size = 0
            private set

        fun add(photoKey: String, faceIndex: Int) {
            if (size == indexes.size) {
                photoKeys = photoKeys.copyOf(size * 2)
                indexes = indexes.copyOf(size * 2)
            }
            photoKeys[size] = photoKey
            indexes[size] = faceIndex
            size++
        }

        /** Whether the planned face at [position] is face [faceIndex] of [photoKey]. */
        fun isAt(position: Int, photoKey: String, faceIndex: Int): Boolean =
            position < size && indexes[position] == faceIndex && photoKeys[position] == photoKey
    }

    private class ExportPlan(
        val nameByPersonId: Map<Long, String>,
        val keys: Collection<String>,
        private val nameSet: Set<String>,
        val faces: PlannedFaces,
        val manualPhotos: List<PersonManualPhotoEntity>,
        val notPersons: List<ExportedMark>,
        val covers: List<PersonCoverEntity>,
        val markers: List<String>,
    ) {
        val names: List<String> = nameSet.sorted()

        fun header(origin: FaceSnapshotOrigin) = MlSnapshotHeader.Faces(
            deviceId = origin.device.id,
            deviceLabel = origin.device.label,
            createdAtMillis = origin.createdAtMillis,
            faceModelVersion = origin.faceModelVersion,
            faceModelSha256 = origin.faceModelSha256,
            clusterParamsVersion = origin.clusterParamsVersion,
            embeddingDim = FACE_EMBEDDING_DIM,
            keyCount = keys.size,
            personCount = names.size,
            faceCount = faces.size,
            manualPhotoCount = manualPhotos.size,
            notPersonCount = notPersons.size,
            coverCount = covers.size,
            scanMarkerCount = markers.size,
        )

        /** [face] as its snapshot record, or null for a face the plan left out. */
        fun faceRecord(face: FaceEntity): MlSnapshotRecord.Face? {
            if (!exportsFace(face.id, face.photoKey, face.embedding.size)) return null
            return MlSnapshotRecord.Face(
                photoKey = face.photoKey,
                faceIndex = faceIndexIn(face.id, face.photoKey) ?: return null,
                left = face.left,
                top = face.top,
                right = face.right,
                bottom = face.bottom,
                landmarks = landmarkPoints(face.landmarks),
                score = face.score,
                blur = face.blur,
                rejected = face.rejected,
                personName = groupedName(face.personId, face.rejected, nameByPersonId),
                confirmedName = snapshotName(face.manualName),
                embedding = face.embedding,
            )
        }

        /** Whether every name [face] carries is one the name table lists. */
        fun holdsNames(face: MlSnapshotRecord.Face): Boolean =
            face.personName.let { it == null || it in nameSet } && face.confirmedName.let { it == null || it in nameSet }
    }

    private companion object {
        /** A face the snapshot carries: on a cloud photo, with an id of the index's own `photoKey#index`
         *  form, and an embedding of this pipeline's width. */
        fun exportsFace(id: String, photoKey: String, embeddingBytes: Int): Boolean =
            embeddingBytes == FACE_EMBEDDING_BYTES && carriesPhotoKey(photoKey) && faceIndexIn(id, photoKey) != null

        /** The named person a face is grouped under; a removed face belongs to no one. */
        fun groupedName(personId: Long?, rejected: Boolean, nameByPersonId: Map<Long, String>): String? =
            if (rejected || personId == null) null else nameByPersonId[personId]
    }
}

/**
 * The app-private scratch files a snapshot's plaintext passes through on its way into or out of the
 * envelope. Each is deleted by its user once done; one a killed process left behind is swept at the next
 * app start ([MlSyncScratch]) or by the next export or import, so a face index never lingers in the clear.
 */
internal object FaceSnapshotScratch {
    const val DIRECTORY = "face_snapshot"
    private const val STALE_AFTER_MS = 60 * 60 * 1000L

    fun newFile(context: Context): File {
        val dir = File(context.cacheDir, DIRECTORY)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_AFTER_MS) it.delete() }
        return File.createTempFile("faces", ".tmp", dir)
    }
}
