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

package eu.akoos.photos.domain.usecase

import androidx.room.withTransaction
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import javax.inject.Inject

/**
 * From the [oldKey, newKey] pairs of photos that just paired device-to-cloud, the ones whose face data
 * should follow the photo to the cloud key: every pair whose device copy was scanned. The faces move when
 * the cloud copy was not scanned, and merge into the cloud copy's own when it was, so no device-keyed set
 * is left next to a cloud-keyed one. Pure so the walk's filter is unit-testable without a database.
 */
fun backupRekeyPairs(
    syncedKeyPairs: List<Pair<String, String>>,
    scannedKeys: Set<String>,
): List<Pair<String, String>> =
    syncedKeyPairs.filter { (oldKey, newKey) -> oldKey != newKey && oldKey in scannedKeys }

/**
 * Moves a single photo's face data from its device key to its cloud key when it is backed up, so the
 * faces, scan marker and curation follow the photo's cloud identity instead of a re-scan piling up a
 * second face set. When the cloud key was scanned too (a restored face snapshot wrote its faces), the two
 * sets merge onto the cloud key instead, as [planFaceRekeyMerge] lays out, and the people they touch get a
 * fresh count and cover. Account only (a guest has no cloud copy). Never throws to the caller.
 */
class RekeyPhotoFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personCoverDao: PersonCoverDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personDao: PersonDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val appDatabase: AppDatabase,
) {
    /**
     * Returns true when it moved or merged the photo's data. False for a self pair or a guest, when the
     * write failed, or when [writeAllowed] refused: it is asked inside the transaction, so nothing the
     * caller fences against can commit between the check and the write, and a refused call writes nothing.
     */
    suspend fun invoke(
        userId: String,
        oldKey: String,
        newKey: String,
        writeAllowed: () -> Boolean = { true },
    ): Boolean {
        if (oldKey == newKey || userId == PhotoLocationEntity.LOCAL_USER) return false
        return runCatching {
            appDatabase.withTransaction {
                if (!writeAllowed()) return@withTransaction false
                if (faceScanDao.isScanned(userId, newKey)) {
                    merge(userId, oldKey, newKey)
                } else {
                    faceDao.rekeyPhoto(userId, oldKey, newKey)
                    faceScanDao.rekeyPhoto(userId, oldKey, newKey)
                    personCoverDao.rekeyPhoto(userId, oldKey, newKey)
                    personManualPhotoDao.rekeyPhoto(userId, oldKey, newKey)
                    notPersonDao.rekeyPhoto(userId, oldKey, newKey)
                }
                true
            }
        }.getOrDefault(false)
    }

    /** Folds [oldKey]'s face data into [newKey]'s, both scanned, inside the caller's transaction. */
    private suspend fun merge(userId: String, oldKey: String, newKey: String) {
        val device = faceDao.curatedFacesOnPhoto(userId, oldKey)
        val plan = planFaceRekeyMerge(
            oldKey = oldKey,
            newKey = newKey,
            device = device,
            cloud = faceDao.curatedFacesOnPhoto(userId, newKey),
            deviceMarks = notPersonDao.marksForPhoto(userId, oldKey),
            cloudMarks = notPersonDao.marksForPhoto(userId, newKey),
            namedPeople = personDao.namedPeopleForUser(userId)
                .mapNotNull { person -> person.displayName?.takeIf { it.isNotBlank() }?.let { person.id to it } }
                .toMap(),
            deviceManualNames = personManualPhotoDao.namesForPhoto(userId, oldKey).toSet(),
            deviceCovers = personDao.peopleCoveredBy(userId, device.map { it.id })
                .mapNotNull { person -> person.coverFaceId?.let { person.id to it } }
                .toMap(),
            deviceHiRes = faceScanDao.hiResScanned(userId, oldKey) == true,
        )
        for ((name, ids) in plan.confirm) faceDao.labelFacesByIds(ids, name)
        if (plan.remove.isNotEmpty()) faceDao.rejectByIds(plan.remove)
        for ((from, to) in plan.moved) faceDao.moveFace(userId, from, to, newKey)
        // What is left on the old key is the device faces merged into a cloud face.
        faceDao.deleteByPhotoKeys(userId, listOf(oldKey))
        if (plan.marks.isNotEmpty()) notPersonDao.add(plan.marks)
        notPersonDao.deleteForPhoto(userId, oldKey)
        personManualPhotoDao.mergePhoto(userId, oldKey, newKey)
        personCoverDao.rekeyPhoto(userId, oldKey, newKey)
        for ((personId, faceId) in plan.covers) personDao.updateCover(personId, faceId)
        if (plan.raiseHiRes) faceScanDao.markHiResScanned(userId, listOf(newKey))
        faceScanDao.deleteByPhotoKeys(userId, listOf(oldKey))
        for (personId in plan.people) refreshPerson(userId, personId)
    }

    /**
     * Recomputes a person's cached count (distinct photos across its kept faces and manual adds) and cover,
     * and drops its cached centroid, as a merging import does for the people it touched. The cover is the
     * chosen cover photo's face, else the cover it holds while that is still one of its kept faces (so a
     * cover moved off a merged face stays put), else its clearest face.
     */
    private suspend fun refreshPerson(userId: String, personId: Long) {
        val person = personDao.personById(personId)?.takeIf { it.userId == userId } ?: return
        val name = person.displayName?.takeIf { it.isNotBlank() }
        val manual = name?.let { personManualPhotoDao.photoKeysForNameList(userId, it) }.orEmpty()
        val count = (faceDao.distinctPhotoKeysForPerson(userId, personId) + manual).toHashSet().size
        val cover = name?.let { personCoverDao.photoKeyForName(userId, it) }
            ?.let { faceDao.topFaceForPersonPhoto(userId, personId, it) }
            ?: person.coverFaceId?.takeIf { id ->
                faceDao.faceById(id)?.let { it.userId == userId && it.personId == personId && !it.rejected } == true
            }
            ?: faceDao.topFaceForPerson(userId, personId)
        personDao.updateCoverAndCount(personId, cover, count)
        clusterSummaryDao.deleteByPerson(personId)
    }
}
