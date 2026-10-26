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

package eu.akoos.photos.data.sharedbackup

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.util.SyncDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/**
 * The two ways to delete the face data while the Drive backup is on. Both run on the app's scope, so leaving the
 * settings page does not stop them half-way.
 */
class FaceDataDelete @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val store: SharedBackupStore,
    private val runner: SharedBackupRunner,
    private val scheduler: SharedBackupScheduler,
    @AppScope private val appScope: CoroutineScope,
) {

    /**
     * Only on this phone: the face data here goes and the scan starts over, and a round reads every copy on Drive
     * back in at once, this install's own included, so the faces and names saved there return.
     */
    fun onlyHere(userId: UserId) {
        appScope.launch {
            faceIndexingScheduler.wipeFaceData(userId)
            runner.forgetApplied(userId, SharedBackupKind.FACES)
            store.forgetOwnCopyTaken()
            SyncDiagnostics.log("face data deleted on this device, reading it back from Drive")
            scheduler.loadNow()
        }
    }

    /**
     * Everywhere: face recognition turns off and the face data here goes, the delete is noted with its time, and a
     * round at once writes this device's copy carrying it and moves the other devices' face copies on Drive to the
     * trash. Each other device deletes its own face data and turns face recognition off when it reads that copy,
     * and a copy one of them wrote before it read it is never taken in again.
     */
    fun everywhere(userId: UserId) {
        appScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = false }
            faceIndexingScheduler.wipeFaceData(userId, thenIndex = false)
            val now = System.currentTimeMillis()
            store.recordFacesWiped(now, madeHere = true)
            store.markChanged(listOf(SharedBackupKind.FACES), now, byUser = true)
            SyncDiagnostics.log("face data deleted everywhere")
            scheduler.saveNow()
        }
    }
}
