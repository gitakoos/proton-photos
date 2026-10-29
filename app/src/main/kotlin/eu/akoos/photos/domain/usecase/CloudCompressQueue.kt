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

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PendingCloudCompressDao
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.worker.CloudCompressWorker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager

/**
 * Queues photos and videos on Drive for compression in place and hands them to [CloudCompressWorker]. The
 * rows go in slices of [CHUNK] on the app scope, so a selection of tens of thousands neither blocks the
 * screen nor dies with it.
 */
@Singleton
class CloudCompressQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PendingCloudCompressDao,
    private val accountManager: AccountManager,
    @AppScope private val appScope: CoroutineScope,
    private val network: NetworkObserver,
) {
    /** True when a start right now would wait: the back-up is set to Wi-Fi only and the phone is not on
     *  an unmetered network. */
    suspend fun waitsForWifi(): Boolean =
        (context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] ?: false) && !network.isUnmetered.value

    /** How many photos are waiting or being compressed. */
    val pending: Flow<Int> = dao.observeCount()

    /** Queues the Drive-only photos and videos among [items] and returns how many that is. A photo that is
     *  also on this phone, or one of someone else's albums, is not offered and is left out here too. */
    fun enqueue(items: List<GalleryItem>): Int {
        val linkIds = compressibleLinkIds(items)
        if (linkIds.isEmpty()) return 0
        appScope.launch {
            val userId = accountManager.getPrimaryUserId().first()?.id ?: return@launch
            val now = System.currentTimeMillis()
            linkIds.chunked(CHUNK).forEachIndexed { slice, ids ->
                // One enqueue time per slice, rising, so the queue keeps the selection's order. A photo
                // still queued from before is asked for again, so an earlier Cancel does not drop it.
                dao.requeue(ids, now + slice)
                dao.insertAll(ids.map { PendingCloudCompressEntity(linkId = it, userId = userId, enqueuedAt = now + slice) })
            }
            start()
        }
        return linkIds.size
    }

    /**
     * Stops the compression without interrupting a photo halfway: every queued photo not started yet is
     * dropped, while the ones being compressed right now and any whose copy is already on Drive are finished,
     * so no photo is left on Drive twice. A running drain sees the stop before each next photo; a drain only
     * waiting for its turn is called off.
     */
    fun cancel() {
        val cancelledAt = System.currentTimeMillis()
        CloudCompressWorker.stopRequestedAt.value = cancelledAt
        appScope.launch {
            val drainRunning = CloudCompressWorker.activeDrains.value > 0
            if (!drainRunning) {
                WorkManager.getInstance(context).cancelUniqueWork(CloudCompressWorker.UNIQUE_NAME)
            }
            dao.clearNotStarted(cancelledAt, CloudCompressWorker.running.toList())
            if (!drainRunning && dao.count() > 0) start()
        }
    }

    /** Picks up photos still queued from an earlier session (a run the system stopped, a reboot). */
    fun resumeIfPending() {
        appScope.launch { if (dao.count() > 0) start() }
    }

    private suspend fun start() {
        val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] ?: false
        CloudCompressWorker.enqueue(context, wifiOnly)
    }

    private companion object {
        const val CHUNK = 500
    }
}

/** The link of every photo or video in [items] that lives only on Drive: the ones compression in place
 *  applies to. Pure. */
internal fun compressibleLinkIds(items: List<GalleryItem>): List<String> = items.mapNotNull { item ->
    val cloud = (item as? GalleryItem.CloudOnly)?.cloud ?: return@mapNotNull null
    val mime = cloud.mimeType.lowercase()
    cloud.linkId.takeIf { mime.startsWith("image/") || mime.startsWith("video/") }
}.distinct()
