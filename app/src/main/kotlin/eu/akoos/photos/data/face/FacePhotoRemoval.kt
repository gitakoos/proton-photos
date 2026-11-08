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
package eu.akoos.photos.data.face

import androidx.room.withTransaction
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.usecase.RekeyPhotoFacesUseCase
import eu.akoos.photos.util.flatMapSqlChunks
import javax.inject.Inject

/**
 * Brings [personIds], people of [account] that just lost faces, up to date inside the caller's transaction: each
 * one's photo count is its kept faces' distinct photos plus, for a named person, the photos added by hand; its
 * cover moves to the chosen cover photo's face, else stays while still one of its kept faces, else goes to its
 * clearest face; its cached centroid is dropped. A person left with no kept face and no photo added by hand is
 * removed, as a regroup would leave it out.
 */
internal suspend fun refreshPeopleAfterFaceLoss(db: AppDatabase, account: String, personIds: Collection<Long>) {
    if (personIds.isEmpty()) return
    val faceDao = db.faceDao()
    val personDao = db.personDao()
    val manualDao = db.personManualPhotoDao()
    val emptied = ArrayList<Long>()
    for (person in personDao.peopleByIds(personIds)) {
        if (person.userId != account) continue
        val name = person.displayName?.takeIf { it.isNotBlank() }
        val facePhotos = faceDao.distinctPhotoKeysForPerson(account, person.id)
        val manual = name?.let { manualDao.photoKeysForNameList(account, it) }.orEmpty()
        if (facePhotos.isEmpty() && manual.isEmpty()) {
            emptied += person.id
            continue
        }
        val cover = name?.let { db.personCoverDao().photoKeyForName(account, it) }
            ?.let { faceDao.topFaceForPersonPhoto(account, person.id, it) }
            ?: person.coverFaceId?.takeIf { id ->
                faceDao.faceById(id)?.let { it.userId == account && it.personId == person.id && !it.rejected } == true
            }
            ?: faceDao.topFaceForPerson(account, person.id)
        personDao.updateCoverAndCount(person.id, cover, (facePhotos + manual).toHashSet().size)
    }
    db.clusterSummaryDao().deleteByPersons(personIds)
    if (emptied.isNotEmpty()) personDao.deleteByIds(emptied)
}

/**
 * Takes photos' face data off this device when the photos are gone for good or put away: the permanent cloud
 * delete, the reaper's sweep of photos that left the library, and a hide into the vault. Each drop is one
 * transaction with the people it touches brought up to date ([refreshPeopleAfterFaceLoss]), and counts as an
 * edit ([FaceEditCounter]) so a regroup that read the faces before it groups again.
 */
class FacePhotoRemoval @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncStateDao: SyncStateDao,
    private val rekeyPhotoFaces: RekeyPhotoFacesUseCase,
    private val faceEdits: FaceEditCounter,
) {
    /** Drops every face and scan marker of [photoKeys] for [account] and refreshes the people they were in. */
    suspend fun dropPhotos(account: String, photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        faceEdits.edit {
            appDatabase.withTransaction {
                val people = appDatabase.faceDao().personIdsOnPhotos(account, photoKeys)
                appDatabase.faceDao().deleteByPhotoKeys(account, photoKeys)
                appDatabase.faceScanDao().deleteByPhotoKeys(account, photoKeys)
                refreshPeopleAfterFaceLoss(appDatabase, account, people)
            }
        }
    }

    /**
     * Reaps [reap], the face keys [FaceReaper.facesToReap] found gone. A device photo that is backed up keeps
     * its face data: it moves onto the Drive copy's key ([RekeyPhotoFacesUseCase]), so freeing the device copy
     * never loses a face, a name or a mark, unless [cloudGone] says the Drive copy is gone too. A photo moved
     * into the vault has nothing to keep here. A key whose move failed stays for the next pass; the rest are
     * dropped. Returns how many keys moved and dropped.
     */
    suspend fun reap(account: String, reap: Set<String>, cloudGone: (String) -> Boolean): ReapOutcome {
        if (reap.isEmpty()) return ReapOutcome(moved = 0, dropped = 0)
        val devicePhotos = reap.filter { it.startsWith("content://") }
        val cloudKeyOf = if (account == PhotoLocationEntity.LOCAL_USER || devicePhotos.isEmpty()) emptyMap()
            else devicePhotos.flatMapSqlChunks { syncStateDao.getByUris(it) }
                .filter { it.userId == account && it.status != SyncStatus.HIDDEN }
                .mapNotNull { row -> row.cloudFileId?.let { row.localUri to it } }
                .toMap()
        val plan = FaceReaper.planReap(reap, cloudKeyOf, cloudGone)
        var moved = 0
        for ((deviceKey, cloudKey) in plan.moves) {
            if (faceEdits.edit { rekeyPhotoFaces.invoke(account, deviceKey, cloudKey) }) moved++
        }
        dropPhotos(account, plan.drops)
        return ReapOutcome(moved = moved, dropped = plan.drops.size)
    }

    data class ReapOutcome(val moved: Int, val dropped: Int)
}
