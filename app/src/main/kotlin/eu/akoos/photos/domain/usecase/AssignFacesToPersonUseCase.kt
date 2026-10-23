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
import eu.akoos.photos.data.db.dao.FaceOnPhoto
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.PersonEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceIndexingScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/**
 * Assigns one or more faces to a person: an existing one picked by id, or a typed name, which resolves
 * to the account's person of that name (ignoring case) or creates one. The faces move onto the person at
 * once and are confirmed under its name, so the choice holds across the next rebuild, and a "not this
 * person" mark for that name on them is lifted. Moving faces onto an unnamed person clears their
 * confirmation instead, since an old confirmed name would pull them back to it on a rebuild.
 *
 * The people the faces came from get a fresh count and cover, one left with no faces is removed, and
 * every touched person's cached centroid is dropped so no stale summary places new faces. A new person
 * is inserted already carrying its count and cover, so an empty-person sweep never removes it. All
 * writes land in one transaction, keyed to the account or, for a guest, the local partition.
 *
 * Returns the person's id, or -1 when nothing was assigned (a blank name, a person that is not the
 * account's, the Unsorted bucket, or no matching face). With `recluster` set, one regroup is requested
 * afterwards so the name pulls in the person's other faces; a caller assigning over several calls passes
 * false and requests it once itself.
 */
class AssignFacesToPersonUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val personCoverDao: PersonCoverDao,
    private val notPersonDao: NotPersonDao,
    private val clusterSummaryDao: ClusterSummaryDao,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val appDatabase: AppDatabase,
) {
    /** The person the faces go to. */
    sealed interface Target {
        /** An existing person, picked by id. */
        data class Existing(val personId: Long) : Target

        /** A typed name: the person already called that, ignoring case, else a new one. */
        data class Named(val name: String) : Target
    }

    suspend operator fun invoke(
        userId: UserId?,
        faceIds: Collection<String>,
        target: Target,
        recluster: Boolean = true,
    ): Long = withContext(Dispatchers.IO) {
        if (faceIds.isEmpty()) return@withContext -1L
        val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
        val personId = appDatabase.withTransaction { assign(account, faceIds, target) }
        if (recluster && personId >= 0) faceIndexingScheduler.requestRecluster(userId)
        personId
    }

    private suspend fun assign(account: String, faceIds: Collection<String>, target: Target): Long {
        val faces = faceDao.facesByIds(account, faceIds)
        if (faces.isEmpty()) return -1L
        val person = when (target) {
            is Target.Existing -> personDao.personById(target.personId)
                ?.takeIf { it.userId == account && !it.isOther }
                ?: return -1L
            is Target.Named -> {
                val typed = target.name.trim()
                if (typed.isEmpty()) return -1L
                findByName(account, typed) ?: createPerson(account, typed, faces)
            }
        }
        val name = person.displayName?.takeIf { it.isNotBlank() }
        val ids = faces.map { it.id }
        faceDao.reassignFacesToPerson(ids, person.id)
        if (name != null) {
            faceDao.labelFacesByIds(ids, name)
            for (id in ids) notPersonDao.deleteMark(account, name, id)
        } else {
            faceDao.clearManualNameByIds(ids)
        }
        refresh(account, person.id, name)
        // Only a person whose faces changed loses its cached centroid, which the next grouping then recomputes.
        if (faces.any { it.personId != person.id }) clusterSummaryDao.deleteByPerson(person.id)

        val sourceIds = faces.mapNotNullTo(HashSet()) { it.personId }
        sourceIds.remove(person.id)
        var emptied = false
        for (sourceId in sourceIds) {
            val source = personDao.personById(sourceId)?.takeIf { it.userId == account } ?: continue
            if (faceDao.faceCountForPerson(account, sourceId) == 0) {
                personDao.updateCoverAndCount(sourceId, null, 0)
                emptied = true
            } else {
                refresh(account, sourceId, source.displayName?.takeIf { it.isNotBlank() })
            }
            clusterSummaryDao.deleteByPerson(sourceId)
        }
        if (emptied) {
            personDao.deleteEmpty(account)
            clusterSummaryDao.deleteOrphansForUser(account)
        }
        return person.id
    }

    /** The account's person called [name]: an exact match first, else one whose name differs only in
     *  case (the one with the most photos when several do). */
    private suspend fun findByName(account: String, name: String): PersonEntity? {
        val named = personDao.namedPeopleForUser(account).filter { !it.displayName.isNullOrBlank() }
        return named.firstOrNull { it.displayName == name }
            ?: named.filter { it.displayName.equals(name, ignoreCase = true) }.maxByOrNull { it.faceCount }
    }

    /** A new person for [name], inserted already carrying its photo count and its clearest face as the
     *  cover, so an empty-person sweep never removes it. */
    private suspend fun createPerson(account: String, name: String, faces: List<FaceOnPhoto>): PersonEntity {
        val photos = faces.mapTo(HashSet()) { it.photoKey }
        photos += personManualPhotoDao.photoKeysForNameList(account, name)
        val person = PersonEntity(
            userId = account,
            displayName = name,
            coverFaceId = faces.maxByOrNull { it.score }?.id,
            faceCount = photos.size,
        )
        return person.copy(id = personDao.insert(person))
    }

    /** Recomputes a person's cached count (distinct photos across its faces and manual adds) and cover
     *  (the chosen cover while that photo still holds one of its faces, else the clearest face). */
    private suspend fun refresh(account: String, personId: Long, name: String?) {
        val manual = name?.let { personManualPhotoDao.photoKeysForNameList(account, it) }.orEmpty()
        val count = (faceDao.distinctPhotoKeysForPerson(account, personId) + manual).toHashSet().size
        val chosen = name?.let { personCoverDao.photoKeyForName(account, it) }
            ?.let { key -> faceDao.topFaceForPersonPhoto(account, personId, key) }
        personDao.updateCoverAndCount(personId, chosen ?: faceDao.topFaceForPerson(account, personId), count)
    }
}
