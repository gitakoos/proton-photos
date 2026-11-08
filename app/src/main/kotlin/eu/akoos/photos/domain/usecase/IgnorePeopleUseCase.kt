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
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.face.FaceCurationLog
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.refreshPeopleAfterFaceLoss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "This is not a person" for whole groups (a statue, a poster, a repeated false detection): every face of
 * each of the people is rejected so it never groups again, the same thing's look-alikes elsewhere in the
 * library go with them ([HideSimilarFacesUseCase]), a named person's name-keyed curation is dropped, and the
 * people are removed. The people the look-alikes came from are brought up to date. The look-alikes are found
 * first, by reading alone; every write then lands in one transaction, counted as one edit, so a failure part
 * way leaves the people as they were. Shared by the person page and the review screen. Returns how many
 * look-alikes were removed besides the people's own faces.
 */
class IgnorePeopleUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val appDatabase: AppDatabase,
    private val hideSimilarFaces: HideSimilarFacesUseCase,
    private val faceEdits: FaceEditCounter,
) {
    suspend operator fun invoke(account: String, personIds: Collection<Long>): Int = withContext(Dispatchers.IO) {
        val dismissed = personIds.toSet()
        if (dismissed.isEmpty()) return@withContext 0
        val lookAlikes = LinkedHashSet<String>()
        for (personId in dismissed) {
            val reference = faceDao.facesForPerson(account, personId).map { unpackEmbedding(it.embedding) }
            lookAlikes += hideSimilarFaces.findLookAlikes(account, reference, dismissed)
        }
        faceEdits.edit {
            appDatabase.withTransaction { dismiss(account, dismissed, lookAlikes) }
        }
    }

    private suspend fun dismiss(account: String, dismissed: Set<Long>, lookAlikes: Collection<String>): Int {
        val people = personDao.peopleByIds(dismissed).filter { it.userId == account }
        for (person in people) faceDao.rejectAllForPerson(account, person.id)
        val removed = hideSimilarFaces.removeLookAlikes(account, lookAlikes, dismissed)
        val now = System.currentTimeMillis()
        for (name in people.mapNotNull { it.displayName?.takeIf(String::isNotBlank) }.distinct()) clearCuration(account, name, now)
        for (person in people) personDao.updateCoverAndCount(person.id, null, 0)
        personDao.deleteEmpty(account)
        clusterSummaryDao.deleteByPersons(dismissed)
        refreshPeopleAfterFaceLoss(appDatabase, account, removed.people - dismissed)
        clusterSummaryDao.deleteOrphansForUser(account)
        return removed.faces
    }

    /** Drops the name-keyed curation of a dismissed person called [name], noting each removal for the shared copy. */
    private suspend fun clearCuration(account: String, name: String, now: Long) {
        val manualDao = appDatabase.personManualPhotoDao()
        val notPersonDao = appDatabase.notPersonDao()
        val coverDao = appDatabase.personCoverDao()
        val curationDao = appDatabase.faceCurationTimeDao()
        val attached = manualDao.photoKeysForNameList(account, name)
        val marks = notPersonDao.allForUser(account).filter { samePersonName(it.personName, name) }.map { it.faceId }
        val hadCover = coverDao.photoKeyForName(account, name) != null
        manualDao.clearForName(account, name)
        notPersonDao.clearForName(account, name)
        coverDao.clearForName(account, name)
        FaceCurationLog.manualPhotos(curationDao, account, name, attached, removed = true, now)
        FaceCurationLog.notPerson(curationDao, account, name, marks, removed = true, now)
        if (hadCover) FaceCurationLog.cover(curationDao, account, name, removed = true, now)
    }
}
