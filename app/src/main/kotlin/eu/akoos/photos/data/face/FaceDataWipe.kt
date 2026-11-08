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

import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.dao.clearForUser

/**
 * Deletes every detected face, person, name, scan marker and correction of [account]. The caller runs it inside a
 * transaction, so the tables never stand half cleared.
 */
internal suspend fun AppDatabase.clearFaceData(account: String) {
    faceDao().clearForUser(account)
    personDao().clearForUser(account)
    faceScanDao().clearForUser(account)
    personManualPhotoDao().clearForUser(account)
    notPersonDao().clearForUser(account)
    personCoverDao().clearForUser(account)
    personNameDao().clearForUser(account)
    faceCurationTimeDao().clearForUser(account)
    clusterSummaryDao().clearForUser(account)
}
