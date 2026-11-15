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

package eu.akoos.photos.sdkbench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.repository.drive.AlbumService
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotoStreamService
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.data.repository.drive.ThumbnailHelpers
import eu.akoos.photos.data.repository.drive.ThumbnailUrlInfo
import eu.akoos.photos.data.repository.drive.cachedThumbnailFile
import eu.akoos.photos.data.sdk.SdkDriveClientProvider
import eu.akoos.photos.data.sdk.SdkListingOps
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.network.data.ApiProvider
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.ThumbnailType

/**
 * DEBUG ONLY (debug source set, never in a release). Times the Drive SDK reads the app keeps on its own
 * requests because they were measured slower, next to the app's own request for the same data, so a new
 * SDK version can be measured again on a real account. Reads only; it changes nothing on Drive.
 *
 *  - NODES: the SDK's node read for N library photos against the app's own batch read of the same links.
 *  - THUMBS: the SDK's thumbnail read for 24 photos (first answer and all) and for one photo.
 *  - ALBUMS: the SDK's album list (ids, then their nodes) against the app's own album list.
 *  - TIMELINE: the SDK's whole timeline listing.
 *
 * With `--ez routes true` it times instead each read that has its own switch, both ways, as the switch on
 * and off runs it: the timeline (the SDK listing filled from the links, against the app's own pages), the
 * album list, the largest album's photos, and N grid thumbnails written to a scratch folder.
 *
 * Trigger:  adb shell am broadcast -a eu.akoos.photos.SDK_READ_BENCH --ei nodes 100 -p <applicationId>
 *           adb shell am broadcast -a eu.akoos.photos.SDK_READ_BENCH --ez routes true --ei nodes 24 -p <applicationId>
 * Output:   adb logcat -s SdkReadBench
 */
class SdkReadBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val nodes = intent.getIntExtra("nodes", 100).coerceIn(1, 1000)
        val routes = intent.getBooleanExtra("routes", false)
        val appContext = context.applicationContext
        // Not goAsync: the SDK reads outlast the broadcast timeout, so the work runs in its own thread.
        Thread {
            try {
                runBlocking {
                    val bench = SdkReadBench(appContext)
                    if (routes) bench.runRoutes(nodes) else bench.run(nodes)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bench failed: ${t.message}", t)
            }
        }.start()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SdkReadBenchDeps {
    fun accountManager(): AccountManager
    fun clientProvider(): SdkDriveClientProvider
    fun shareService(): PhotosShareService
    fun photoListingDao(): PhotoListingDao
    fun linkDetailHelpers(): LinkDetailHelpers
    fun albumService(): AlbumService
    fun photoStreamService(): PhotoStreamService
    fun sdkListingOps(): SdkListingOps
    fun apiProvider(): ApiProvider
    fun thumbnailHelpers(): ThumbnailHelpers
    fun thumbnailDecryptScheduler(): ThumbnailDecryptScheduler
    fun cryptoServiceClient(): CryptoServiceClient
}

private const val TAG = "SdkReadBench"

private class SdkReadBench(private val context: Context) {
    private val deps = EntryPointAccessors.fromApplication(context, SdkReadBenchDeps::class.java)

    /** Each read with its own switch, the SDK's way and the app's own way. */
    suspend fun runRoutes(thumbCount: Int) {
        val userId = deps.accountManager().getPrimaryUserId().first() ?: error("signed out")
        val volumeId = deps.shareService().getVolumeId(userId)
        Log.i(TAG, "routes start")

        step("TIMELINE route sdk") { "${deps.photoStreamService().timelineViaSdk(userId, volumeId).size} photos" }
        step("TIMELINE route app") {
            val manager = deps.apiProvider().get<DriveApiService>(userId)
            var count = 0
            var cursor: String? = null
            do {
                val links = manager.invoke { getPhotoLinks(volumeId, cursor) }.valueOrThrow.links
                count += links.size
                cursor = links.lastOrNull()?.linkId
            } while (links.isNotEmpty())
            "$count photos, without the walk's pause between pages"
        }

        step("ALBUM_LIST route sdk") { "${deps.sdkListingOps().albums(userId, volumeId).size} albums" }
        step("ALBUM_LIST route app") { "${deps.albumService().albumListOwnPath(userId, volumeId).size} albums" }

        val album = runCatching { deps.albumService().albumListOwnPath(userId, volumeId) }.getOrNull()
            ?.maxByOrNull { it.photoCount }
        if (album != null) {
            step("ALBUM_PHOTOS route sdk") { "${deps.sdkListingOps().albumPhotos(userId, volumeId, album.linkId).size} photos" }
            step("ALBUM_PHOTOS route app") { "${deps.albumService().albumChildrenOwnPath(userId, volumeId, album.linkId).size} photos" }
        }

        // Grid thumbnails of library photos in the photos root, which the switch covers, written to a scratch
        // folder so the gallery's own cache neither answers nor changes.
        val scratch = File(context.cacheDir, "sdk_bench_thumbs").apply { deleteRecursively(); mkdirs() }
        val rootLinkId = deps.shareService().photosRootLinkId()
        val rows = deps.photoListingDao().getByLinkIds(deps.photoListingDao().getAllLinkIds(userId.id).take(500))
            .filter { it.volumeId == volumeId && it.parentLinkId == rootLinkId }
            .filter { it.serverThumbnailUrl != null && it.contentKeyPacket != null && it.encNodeKey != null && it.encNodePassphrase != null }
            .take(thumbCount)
        step("THUMBNAILS route sdk") {
            var written = 0
            for (row in rows) {
                runCatching {
                    cachedThumbnailFile(scratch, "sdk_${row.linkId.hashCode()}.jpg", row.linkId) {
                        deps.sdkListingOps().thumbnail(userId, volumeId, row.linkId, ThumbnailType.THUMBNAIL)
                    }
                }.onSuccess { written++ }
            }
            "$written of ${rows.size} thumbnails"
        }
        step("THUMBNAILS route app") {
            val rootKey = deps.shareService().getRootLinkKeyBytes(userId) ?: error("root key unavailable")
            val crypto = deps.cryptoServiceClient()
            var written = 0
            for (row in rows) {
                val nodeKey = crypto.decryptNodeKey(row.encNodeKey!!, row.encNodePassphrase!!, rootKey)
                val sessionKey = crypto.decryptSessionKey(row.contentKeyPacket!!, nodeKey)
                val fileName = "app_${row.linkId.hashCode()}.jpg"
                val stored = ThumbnailUrlInfo(bareUrl = row.serverThumbnailUrl!!, token = row.serverThumbnailToken)
                val url = deps.thumbnailHelpers().downloadAndDecryptBinary(stored, nodeKey, sessionKey, row.linkId, scratch, fileName = fileName)
                    ?: deps.thumbnailDecryptScheduler().fetchFreshThumbnailInfo(userId, row.linkId, volumeId)?.let { fresh ->
                        deps.thumbnailHelpers().downloadAndDecryptBinary(fresh, nodeKey, sessionKey, row.linkId, scratch, fileName = fileName)
                    }
                if (url != null) written++
            }
            "$written of ${rows.size} thumbnails"
        }
        scratch.deleteRecursively()
        Log.i(TAG, "routes done")
    }

    suspend fun run(nodeCount: Int) {
        val userId = deps.accountManager().getPrimaryUserId().first() ?: error("signed out")
        val volumeId = deps.shareService().getVolumeId(userId)
        val linkIds = deps.photoListingDao().getAllLinkIds(userId.id).take(nodeCount)
        val uids = linkIds.map { LegacyNodeUid(volumeId = volumeId, linkId = it) }
        Log.i(TAG, "start: ${linkIds.size} library photos")

        step("NODES app") {
            "${deps.linkDetailHelpers().batchFetchLinkDetails(userId, volumeId, linkIds).size} links"
        }
        step("NODES sdk") {
            sdk(userId) { client -> "${client.enumerateNodes(uids).toList().size} nodes" }
        }
        step("THUMBS sdk 24") {
            sdk(userId) { client ->
                val started = System.nanoTime()
                var first = -1L
                var count = 0
                client.enumerateThumbnails(uids.take(24), ThumbnailType.THUMBNAIL).collect {
                    if (first < 0) first = elapsedMs(started)
                    count++
                }
                "$count thumbnails, first after $first ms"
            }
        }
        step("THUMBS sdk 1") {
            sdk(userId) { client -> "${client.enumerateThumbnails(uids.take(1), ThumbnailType.THUMBNAIL).toList().size} thumbnail" }
        }
        step("ALBUMS app") {
            "${deps.albumService().loadAlbums(userId).size} albums"
        }
        step("ALBUMS sdk") {
            sdk(userId) { client ->
                val ids = client.enumerateAlbumNodeUids().toList()
                "${client.enumerateNodes(ids).toList().size} albums"
            }
        }
        step("TIMELINE sdk") {
            sdk(userId) { client -> "${client.enumerateTimeline().toList().size} items" }
        }
        Log.i(TAG, "done")
    }

    private suspend fun <T> sdk(userId: me.proton.core.domain.entity.UserId, block: suspend (me.proton.drive.sdk.ProtonPhotosClient) -> T): T =
        deps.clientProvider().withPhotosClient(userId, block) ?: error("the SDK cannot run on this device")

    private suspend fun step(name: String, block: suspend () -> String) {
        val started = System.nanoTime()
        val result = runCatching { block() }
        val ms = elapsedMs(started)
        result.onSuccess { Log.i(TAG, "$name: $it in $ms ms") }
            .onFailure { Log.w(TAG, "$name: failed after $ms ms: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
}
