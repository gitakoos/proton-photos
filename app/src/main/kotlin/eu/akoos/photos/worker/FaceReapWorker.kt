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

package eu.akoos.photos.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.face.FaceReaper
import eu.akoos.photos.data.repository.drive.CloudTrashService
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.DeviceHealthPolicy
import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import java.util.concurrent.TimeUnit

/**
 * Periodic maintenance that reaps stored face rows + scan markers whose photo has left the library
 * for good, so the on-device biometric tables cannot grow without bound (faces were otherwise
 * cleared only on sign-out or a model swap). The decision is [FaceReaper.facesToReap]: trust-gated,
 * and it KEEPS a photo's faces while it is still live or still restorable from a trash. The
 * immediate prune at a permanent cloud delete lives in the delete path; this worker is the net for
 * photos removed on the device, as a guest, or on another device.
 */
@HiltWorker
class FaceReapWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val deviceHealth: DeviceHealthPolicy,
    private val accountManager: AccountManager,
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val photoListingDao: PhotoListingDao,
    private val cloudTrashService: CloudTrashService,
    private val localMediaRepo: LocalMediaRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Maintenance, so defer while the OS is cooling the device; the daily cadence catches up.
        if (deviceHealth.thermallyThrottled()) return Result.success()

        val signedInUserId = runCatching { accountManager.getPrimaryUserId().first() }.getOrNull()
        val userKey = signedInUserId?.id ?: PhotoLocationEntity.LOCAL_USER

        val faceKeys = runCatching { faceDao.facePhotoKeysForUser(userKey) }.getOrNull().orEmpty()
        if (faceKeys.isEmpty()) return Result.success()

        // Device keyspace (content:// URIs). A non-empty live set proves the MediaStore scan ran;
        // an empty one means a revoked permission or a failed scan, which must NOT read as "every
        // device photo is gone", so device faces stay untouched that pass.
        var liveDeviceUris = emptySet<String>()
        var deviceTrashUris = emptySet<String>()
        var deviceTrustworthy = false
        runCatching {
            liveDeviceUris = localMediaRepo.observeLocalMedia().first().map { it.uri }.toSet()
            deviceTrashUris = localMediaRepo.observeTrashedMedia().first().map { it.uri }.toSet()
            deviceTrustworthy = liveDeviceUris.isNotEmpty()
        }

        // Cloud keyspace (Drive linkIds). Only a signed-in account has cloud faces; a guest has
        // none, so cloud reaping stays off. Both the live listing and the cloud trash must be known
        // (a failed or offline fetch skips cloud reaping this pass) so a restorable photo is kept.
        var liveCloudLinkIds = emptySet<String>()
        var cloudTrashLinkIds = emptySet<String>()
        var cloudTrustworthy = false
        if (signedInUserId != null) {
            runCatching {
                liveCloudLinkIds = photoListingDao.getAllLinkIds(userKey).toSet()
                cloudTrashLinkIds = cloudTrashService.getCloudTrash(signedInUserId).map { it.linkId }.toSet()
                cloudTrustworthy = true
            }.onFailure { Log.w(TAG, "cloud sources unavailable, skipping cloud reap: ${it.message}") }
        }

        val reap = FaceReaper.facesToReap(
            faceKeys = faceKeys,
            liveCloudLinkIds = liveCloudLinkIds,
            cloudTrashLinkIds = cloudTrashLinkIds,
            cloudTrustworthy = cloudTrustworthy,
            liveDeviceUris = liveDeviceUris,
            deviceTrashUris = deviceTrashUris,
            deviceTrustworthy = deviceTrustworthy,
        )
        if (reap.isNotEmpty()) {
            runCatching {
                faceDao.deleteByPhotoKeys(userKey, reap)
                faceScanDao.deleteByPhotoKeys(userKey, reap)
                Log.d(TAG, "reaped ${reap.size} orphaned face key(s)")
            }.onFailure { Log.w(TAG, "face reap delete failed: ${it.message}") }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "face_reap_periodic"
        private const val TAG = "face_reap_worker"
        private const val INTERVAL_HOURS = 24L

        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                // Battery-friendly maintenance; no network gate so a guest and an offline device
                // still reap the device keyspace, while the cloud fetch simply no-ops offline.
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<FaceReapWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
