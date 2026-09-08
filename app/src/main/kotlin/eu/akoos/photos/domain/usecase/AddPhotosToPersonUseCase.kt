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

import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.db.entity.PersonManualPhotoEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import javax.inject.Inject

/**
 * Attach photos to a person, shared by every surface that offers "add to person" (the person page's
 * picker, the timeline selection, an album's selection). The membership is stored against the person's
 * NAME, which survives a clustering rebuild, so the person must be named first; a call for an unnamed
 * person is a no-op. [photoKeys] are item stableIds, the same keyspace the face index and library use.
 */
class AddPhotosToPersonUseCase @Inject constructor(
    private val accountManager: AccountManager,
    private val personDao: PersonDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val faceDao: FaceDao,
) {
    suspend operator fun invoke(personId: Long, photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        // Runs on-device, so a guest attaches under the local partition, the same key its faces and
        // person rows already use; a non-null account collapses this back to userId.id.
        val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
        val person = personDao.personById(personId) ?: return
        val name = person.displayName
        if (name.isNullOrBlank()) return
        personManualPhotoDao.add(photoKeys.map { PersonManualPhotoEntity(account, name, it) })
        // Display-only, by design: a manual add is stored against the name so the photo shows and
        // survives a rebuild, but its detected face is deliberately NOT auto-labelled. When the
        // person's own face is too small or distant to detect, the only face the detector found in the
        // photo is often a bystander, and labelling that face would teach a wrong identity and poison
        // the person's centroid. Confirmed teaching comes from the suggestion review, where the user
        // actually sees and approves the face.
        // Keep the shown count honest: distinct photos across the person's faces and manual adds.
        val union = (faceDao.distinctPhotoKeysForPerson(account, personId) +
            personManualPhotoDao.photoKeysForNameList(account, name)).toHashSet().size
        personDao.updateCoverAndCount(personId, person.coverFaceId, union)
    }
}
