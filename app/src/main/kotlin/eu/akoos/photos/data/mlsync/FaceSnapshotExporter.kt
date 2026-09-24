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
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.AppDatabase
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
 * photo keys and names the format's tables need are kept. The plaintext goes to an app-private scratch
 * file, deleted once sealed. Both read passes run in one database transaction, so the counts the header
 * declares up front are exactly the rows that follow; a write the indexer makes meanwhile waits for them.
 */
class FaceSnapshotExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val sealing: FaceSnapshotSealing,
) {

    /** Seals [userId]'s face snapshot into [output], which is flushed and left open. Blocking work runs
     *  on the IO dispatcher. */
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
                val written = BufferedOutputStream(FileOutputStream(plaintext)).use { out ->
                    writePlaintext(userId, out, origin)
                }
                FileInputStream(plaintext).use { MlSnapshotEnvelope.seal(it, output, sealing.crypto, keys) }
                output.flush()
                written.copy(plaintextBytes = plaintext.length())
            } finally {
                plaintext.delete()
            }
        }
    }

    /** Streams the plaintext snapshot of [userId]'s face index into [output], which is flushed and left
     *  open. A null [userId] reads the guest partition. */
    internal suspend fun writePlaintext(
        userId: UserId?,
        output: OutputStream,
        origin: FaceSnapshotOrigin,
    ): FaceSnapshotExport = appDatabase.withTransaction {
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val plan = plan(account)
        val writer = MlSnapshotWriter(output, plan.header(origin))
        for (key in plan.keys) writer.write(MlSnapshotRecord.PhotoKey(key))
        for (name in plan.names) writer.write(MlSnapshotRecord.Person(name))
        forEachFace(account) { face -> plan.faceRecord(face)?.let(writer::write) }
        for (row in plan.manualPhotos) writer.write(MlSnapshotRecord.ManualPhoto(row.personName, row.photoKey))
        for (mark in plan.notPersons) writer.write(MlSnapshotRecord.NotPerson(mark.personName, mark.photoKey, mark.faceIndex))
        for (row in plan.covers) writer.write(MlSnapshotRecord.Cover(row.personName, row.photoKey))
        forEachMarker(account) { photoKey, hiRes ->
            writer.write(MlSnapshotRecord.ScanMarker(photoKey, hiRes))
        }
        writer.finish()
        FaceSnapshotExport(
            faces = plan.faceCount,
            people = plan.groupedNames,
            photos = plan.keys.size,
            scanMarkers = plan.markerCount,
            plaintextBytes = 0L,
        )
    }

    /**
     * The first pass: every row the snapshot will hold is picked and counted without reading a single
     * embedding, and the photo keys and names the records use are gathered for the tables that lead the
     * stream. The second pass in [writePlaintext] applies the very same tests to the whole rows.
     */
    private suspend fun plan(account: String): ExportPlan {
        val nameByPersonId = HashMap<Long, String>()
        for (person in appDatabase.personDao().namedPeopleForUser(account)) {
            snapshotName(person.displayName)?.let { nameByPersonId[person.id] = it }
        }
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

        val keys = HashSet<String>()
        val names = HashSet<String>()
        val groupedNames = HashSet<String>()
        val exportedMarkedFaces = HashSet<String>()
        var faceCount = 0
        forEachFaceHead(account) { head ->
            if (!exportsFace(head.id, head.photoKey, head.embeddingBytes)) return@forEachFaceHead
            faceCount++
            keys += head.photoKey
            groupedName(head.personId, head.rejected, nameByPersonId)?.let {
                names += it
                groupedNames += it
            }
            snapshotName(head.manualName)?.let(names::add)
            if (head.id in markedFaceIds) exportedMarkedFaces += head.id
        }
        // A mark travels with the face it names, the way the v3 export kept only the marks of the faces it
        // carried; a mark on a face no longer detected would only bind to whatever face takes its index.
        val notPersons = marks.filter { it.faceId in exportedMarkedFaces }
        for (row in manualPhotos) { keys += row.photoKey; names += row.personName }
        for (mark in notPersons) { keys += mark.photoKey; names += mark.personName }
        for (row in covers) { keys += row.photoKey; names += row.personName }
        var markerCount = 0
        forEachMarker(account) { photoKey, _ ->
            markerCount++
            keys += photoKey
        }
        return ExportPlan(
            nameByPersonId = nameByPersonId,
            keys = keys,
            names = names.sorted(),
            groupedNames = groupedNames.size,
            faceCount = faceCount,
            manualPhotos = manualPhotos,
            notPersons = notPersons,
            covers = covers,
            markerCount = markerCount,
        )
    }

    private suspend inline fun forEachFaceHead(account: String, action: (FaceHead) -> Unit) {
        var after = ""
        while (true) {
            val page = appDatabase.faceDao().faceHeadsAfter(account, after, HEAD_PAGE)
            page.forEach(action)
            if (page.size < HEAD_PAGE) return
            after = page.last().id
        }
    }

    private suspend inline fun forEachFace(account: String, action: (FaceEntity) -> Unit) {
        var after = ""
        while (true) {
            val page = appDatabase.faceDao().facesAfter(account, after, FACE_PAGE)
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

    private class ExportPlan(
        val nameByPersonId: Map<Long, String>,
        val keys: Set<String>,
        val names: List<String>,
        val groupedNames: Int,
        val faceCount: Int,
        val manualPhotos: List<PersonManualPhotoEntity>,
        val notPersons: List<ExportedMark>,
        val covers: List<PersonCoverEntity>,
        val markerCount: Int,
    ) {
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
            faceCount = faceCount,
            manualPhotoCount = manualPhotos.size,
            notPersonCount = notPersons.size,
            coverCount = covers.size,
            scanMarkerCount = markerCount,
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
