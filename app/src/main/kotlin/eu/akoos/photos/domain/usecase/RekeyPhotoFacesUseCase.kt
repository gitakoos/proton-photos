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
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import javax.inject.Inject

/**
 * From the [oldKey, newKey] pairs of photos that just paired device-to-cloud, the ones whose faces
 * should move to the cloud key: the device copy was already scanned and the cloud copy was not, so
 * scanning the Synced copy would otherwise build a duplicate face set. Pure so the walk's filter is
 * unit-testable without a database.
 */
fun backupRekeyPairs(
    syncedKeyPairs: List<Pair<String, String>>,
    scannedKeys: Set<String>,
): List<Pair<String, String>> =
    syncedKeyPairs.filter { (oldKey, newKey) ->
        oldKey != newKey && oldKey in scannedKeys && newKey !in scannedKeys
    }

/**
 * Moves a single photo's face data from its device key to its cloud key when it is backed up, so the
 * faces, scan marker and curation follow the photo's cloud identity instead of a re-scan piling up a
 * second face set. Account only (a guest has no cloud copy). Never throws to the caller.
 */
class RekeyPhotoFacesUseCase @Inject constructor(
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personCoverDao: PersonCoverDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val appDatabase: AppDatabase,
) {
    /** Returns true when it moved the photo's data, false when the new key was already scanned. */
    suspend fun invoke(userId: String, oldKey: String, newKey: String): Boolean {
        if (oldKey == newKey) return false
        return runCatching {
            appDatabase.withTransaction {
                // Re-check inside the transaction: if the cloud copy was scanned in the meantime its
                // own faces exist, and re-keying onto them would collide, so leave both alone.
                if (faceScanDao.isScanned(userId, newKey)) return@withTransaction false
                faceDao.rekeyPhoto(userId, oldKey, newKey)
                faceScanDao.rekeyPhoto(userId, oldKey, newKey)
                personCoverDao.rekeyPhoto(userId, oldKey, newKey)
                personManualPhotoDao.rekeyPhoto(userId, oldKey, newKey)
                notPersonDao.rekeyPhoto(userId, oldKey, newKey)
                true
            }
        }.getOrDefault(false)
    }
}
