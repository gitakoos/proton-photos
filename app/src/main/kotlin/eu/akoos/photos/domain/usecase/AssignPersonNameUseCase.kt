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
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.PersonNameLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.ClusterSummaryDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import javax.inject.Inject

/**
 * Assigns a person cluster a name: folds [fromPersonId] into the existing person named [rawName]
 * (that person absorbs its faces, relabelled so the choice survives a rebuild, and its name-keyed
 * curation re-keys across), then removes the now-empty source. If no person of that name exists, the
 * source is simply renamed to it. Returns the surviving person's id, or -1 on an empty name.
 *
 * This is the one place the fold is spelled out, shared by the person page's merge, its merge
 * suggestion, and the viewer's tap-a-face-to-name, so all three keep the exact same bookkeeping.
 */
class AssignPersonNameUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val appDatabase: AppDatabase,
    private val faceEdits: FaceEditCounter = FaceEditCounter(),
) {
    /**
     * With [confirm] false the folded faces join the person without being confirmed under the name, so a fold
     * the app chose on its own (an auto-merge) stays open to the next rebuild instead of pinning every face.
     */
    suspend operator fun invoke(account: String, fromPersonId: Long, rawName: String, confirm: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            val name = rawName.trim()
            if (name.isEmpty()) return@withContext -1L
            // One transaction, so the people lists read the fold once it is whole and a failed write leaves both
            // people as they were.
            faceEdits.edit {
                appDatabase.withTransaction { fold(account, fromPersonId, name, confirm) }
            }
        }

    private suspend fun fold(account: String, fromPersonId: Long, typed: String, confirm: Boolean): Long {
        // Another person already of this name, spelt as typed or differing only in case, takes the faces in and
        // keeps its own spelling.
        val namesake = personDao.namedPeopleForUser(account)
            .filter { it.id != fromPersonId && samePersonName(it.displayName, typed) }
            .let { same -> same.firstOrNull { it.displayName?.trim() == typed } ?: same.maxByOrNull { it.faceCount } }
        val target = namesake?.id ?: fromPersonId
        val name = namesake?.displayName?.trim() ?: typed
        val faceIds = faceDao.faceIdsForPerson(account, fromPersonId)
        if (target != fromPersonId && faceIds.isNotEmpty()) faceDao.reassignFacesToPerson(faceIds, target)
        if (confirm && faceIds.isNotEmpty()) faceDao.labelFacesByIds(faceIds, name)
        val oldName = personDao.personById(fromPersonId)?.displayName
        if (!oldName.isNullOrBlank() && oldName != name) {
            personManualPhotoDao.rename(account, oldName, name)
            faceDao.renameManualName(account, oldName, name)
            notPersonDao.rename(account, oldName, name)
            personCoverDao.rename(account, oldName, name)
            appDatabase.faceCurationTimeDao().rename(account, oldName, name)
            PersonNameLog.renamed(appDatabase.personNameDao(), account, oldName, name, System.currentTimeMillis())
        } else if (oldName.isNullOrBlank() && target == fromPersonId) {
            PersonNameLog.named(appDatabase.personNameDao(), account, name, System.currentTimeMillis())
        }
        personDao.updateName(target, name)
        val manual = personManualPhotoDao.photoKeysForNameList(account, name)
        val count = (faceDao.distinctPhotoKeysForPerson(account, target) + manual).toHashSet().size
        val cover = resolveCover(account, target, name)
        personDao.updateCoverAndCount(target, cover, count)
        if (target != fromPersonId) {
            personDao.updateCoverAndCount(fromPersonId, null, 0)
            personDao.deleteEmpty(account)
            // The target absorbed the source's faces, so its stored centroid + count are stale, and
            // the removed source leaves an orphan summary. Drop both: the next clustering pass then
            // recomputes the target, and no stale centroid can pull a new face onto a dead person.
            clusterSummaryDao.deleteByPerson(target)
            clusterSummaryDao.deleteOrphansForUser(account)
        }
        return target
    }

    /** The surviving person's cover face: their chosen cover when its photo is still one of their
     *  faces, else the clearest face. */
    private suspend fun resolveCover(account: String, personId: Long, name: String?): String? {
        val chosen = name?.takeIf { it.isNotBlank() }
            ?.let { personCoverDao.photoKeyForName(account, it) }
            ?.let { key -> faceDao.topFaceForPersonPhoto(account, personId, key) }
        return chosen ?: faceDao.topFaceForPerson(account, personId)
    }
}
