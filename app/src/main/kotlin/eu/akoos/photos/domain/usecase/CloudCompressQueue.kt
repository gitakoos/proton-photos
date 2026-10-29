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
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PendingCloudCompressDao
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.worker.CloudCompressWorker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
) {
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
                // One enqueue time per slice, rising, so the queue keeps the selection's order.
                dao.insertAll(ids.map { PendingCloudCompressEntity(linkId = it, userId = userId, enqueuedAt = now + slice) })
            }
            val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] ?: false
            CloudCompressWorker.enqueue(context, wifiOnly)
        }
        return linkIds.size
    }

    /** Stops the drain and drops every queued photo whose copy is not on Drive yet; one already uploaded is
     *  finished, so no photo is left on Drive twice. */
    fun cancel() {
        val cancelledAt = System.currentTimeMillis()
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(CloudCompressWorker.UNIQUE_NAME)
        appScope.launch {
            withTimeoutOrNull(CANCEL_STOP_WAIT_MS) { CloudCompressWorker.activeDrains.first { it == 0 } }
            dao.clearNotStarted(cancelledAt)
            if (dao.count() > 0) {
                val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] ?: false
                CloudCompressWorker.enqueue(context, wifiOnly)
            }
        }
    }

    private companion object {
        const val CHUNK = 500
        const val CANCEL_STOP_WAIT_MS = 10_000L
    }
}

/** The link of every photo or video in [items] that lives only on Drive: the ones compression in place
 *  applies to. Pure. */
internal fun compressibleLinkIds(items: List<GalleryItem>): List<String> = items.mapNotNull { item ->
    val cloud = (item as? GalleryItem.CloudOnly)?.cloud ?: return@mapNotNull null
    val mime = cloud.mimeType.lowercase()
    cloud.linkId.takeIf { mime.startsWith("image/") || mime.startsWith("video/") }
}.distinct()
