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
 * Hides a person from People and from every suggestion without touching its faces: they stay kept, grouped,
 * and out of "not a person". The hidden choice is recorded against the person's NAME, which survives every
 * regrouping. A person with no name is first given a reserved one ([HiddenPersonToken]): its faces are
 * confirmed under it the way naming confirms them, so the regrouping keeps the group together and hidden, and
 * nothing shows the reserved name. One transaction, counted as one edit. Returns the name the choice is
 * recorded under, or null when there is nothing to hide (no such person, another account's, or the Unsorted
 * pile). Works signed out, [account] being the local partition then.
 */
class HidePersonUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val appDatabase: AppDatabase,
    private val faceEdits: FaceEditCounter,
) {
    suspend operator fun invoke(account: String, personId: Long): String? = withContext(Dispatchers.IO) {
        var hiddenName: String? = null
        faceEdits.edit {
            appDatabase.withTransaction { hiddenName = hide(account, personId) }
        }
        hiddenName
    }

    private suspend fun hide(account: String, personId: Long): String? {
        val person = personDao.personById(personId)?.takeIf { it.userId == account && !it.isOther } ?: return null
        val now = System.currentTimeMillis()
        val name = person.displayName?.takeIf { it.isNotBlank() } ?: run {
            val token = HiddenPersonToken.make()
            val faceIds = faceDao.faceIdsForPerson(account, personId)
            if (faceIds.isNotEmpty()) faceDao.labelFacesByIds(faceIds, token)
            personDao.updateName(personId, token)
            PersonNameLog.named(appDatabase.personNameDao(), account, token, now)
            token
        }
        appDatabase.personHiddenDao().set(PersonHiddenEntity(account, name, hidden = true, changedAt = now))
        return name
    }
}
