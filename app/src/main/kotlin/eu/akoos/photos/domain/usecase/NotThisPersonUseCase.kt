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
import eu.akoos.photos.data.db.dao.FaceCurationTimeDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.NotPersonEntity
import eu.akoos.photos.data.face.FaceCurationLog
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.presentation.person.personPhotoCount
import me.proton.core.accountmanager.domain.AccountManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * "This isn't them" for the given faces of a person. The exact faces are recorded as rejected for the
 * person's name (so they never rejoin), then unassigned WITHOUT rejecting, so they recluster and can be
 * suggested for the right person rather than vanishing from People. Any manual membership of
 * [photoKeys] is detached, the cached cover and count are refreshed, all in one transaction counted as one
 * edit, and a regroup is requested.
 *
 * Shared by the person page's "Remove from person" and the viewer's face tag, so both keep the same
 * bookkeeping. A nameless person records no rejection, as the mark is keyed by name.
 */
class NotThisPersonUseCase @Inject constructor(
    private val accountManager: AccountManager,
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val faceCurationTimeDao: FaceCurationTimeDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val appDatabase: AppDatabase,
    private val faceEdits: FaceEditCounter,
) {
    suspend operator fun invoke(account: String, personId: Long, faceIds: List<String>, photoKeys: Collection<String>) {
        faceEdits.edit {
            appDatabase.withTransaction { record(account, personId, faceIds, photoKeys) }
        }
        faceIndexingScheduler.requestRecluster(accountManager.getPrimaryUserId().first())
    }

    private suspend fun record(account: String, personId: Long, faceIds: List<String>, photoKeys: Collection<String>) {
        val name = personDao.personById(personId)?.displayName
        val now = System.currentTimeMillis()
        if (!name.isNullOrBlank() && faceIds.isNotEmpty()) {
            notPersonDao.add(faceIds.map { NotPersonEntity(account, name, it) })
            FaceCurationLog.notPerson(faceCurationTimeDao, account, name, faceIds, removed = false, now)
        }
        faceDao.unassignFaces(faceIds)
        if (!name.isNullOrBlank()) {
            val attached = personManualPhotoDao.photoKeysForNameList(account, name).toHashSet()
            personManualPhotoDao.remove(account, name, photoKeys)
            FaceCurationLog.manualPhotos(faceCurationTimeDao, account, name, photoKeys.filter { it in attached }, removed = true, now)
        }
        val manual = if (name.isNullOrBlank()) emptyList()
            else personManualPhotoDao.photoKeysForNameList(account, name)
        val count = personPhotoCount(faceDao.distinctPhotoKeysForPerson(account, personId), manual)
        val cover = resolveCover(account, personId, name)
        personDao.updateCoverAndCount(personId, cover, count)
        // The unassigned faces changed the cluster, so its stored summary is stale; drop it, and the caller
        // reclusters so the freed faces regroup and the count stays honest.
        clusterSummaryDao.deleteByPerson(personId)
    }

    private suspend fun resolveCover(account: String, personId: Long, name: String?): String? {
        val chosen = name?.takeIf { it.isNotBlank() }
            ?.let { personCoverDao.photoKeyForName(account, it) }
            ?.let { key -> faceDao.topFaceForPersonPhoto(account, personId, key) }
        return chosen ?: faceDao.topFaceForPerson(account, personId)
    }
}
