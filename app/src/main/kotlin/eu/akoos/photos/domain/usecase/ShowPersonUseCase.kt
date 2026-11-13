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
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.entity.PersonHiddenEntity
import eu.akoos.photos.data.face.FaceEditCounter
import eu.akoos.photos.data.face.PersonNameLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Shows a hidden person again: its hidden row becomes a hidden=false row with the time of the change, so a
 * later choice made on another device can still win. A person that was never named is a group labelled with a
 * reserved name ([HiddenPersonToken]) while hidden; showing it takes that label off every face, so the group is
 * an ordinary unnamed one again and reaches the review list. Returns true when labels were taken off, which is
 * when the caller asks the grouping to run again ([eu.akoos.photos.data.face.FaceIndexingScheduler.requestRecluster]);
 * a named person needs none. One transaction, counted as one edit.
 */
class ShowPersonUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val appDatabase: AppDatabase,
    private val faceEdits: FaceEditCounter,
) {
    suspend operator fun invoke(account: String, personId: Long): Boolean = withContext(Dispatchers.IO) {
        var regroup = false
        faceEdits.edit {
            appDatabase.withTransaction { regroup = show(account, personId) }
        }
        regroup
    }

    private suspend fun show(account: String, personId: Long): Boolean {
        val person = personDao.personById(personId)?.takeIf { it.userId == account } ?: return false
        val name = person.displayName?.takeIf { it.isNotBlank() } ?: return false
        val hiddenDao = appDatabase.personHiddenDao()
        if (hiddenDao.isHidden(account, name) != true) return false
        val now = System.currentTimeMillis()
        hiddenDao.set(PersonHiddenEntity(account, name, hidden = false, changedAt = now))
        if (!HiddenPersonToken.isToken(name)) return false
        val faceIds = faceDao.faceIdsForPerson(account, personId)
        faceDao.clearConfirmedName(account, name)
        if (faceIds.isNotEmpty()) faceDao.clearManualNameByIds(faceIds)
        personDao.updateName(personId, null)
        PersonNameLog.unnamed(appDatabase.personNameDao(), account, name, now)
        return true
    }
}
