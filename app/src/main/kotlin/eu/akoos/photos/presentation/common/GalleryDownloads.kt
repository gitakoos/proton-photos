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

package eu.akoos.photos.presentation.common

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.worker.AlbumDownloadWorker
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId

/**
 * Every download a screen starts: saving photos to the device, pinning them for offline viewing and
 * handing an album to its background download. Each one runs on the app's own scope, never on the
 * screen's, so closing the screen right after the tap does not drop it: Search, a place, the viewer
 * and an album all close and are cleared while the photos are still coming down. The screen only
 * waits for the result, and stopping that wait leaves the download running.
 *
 * A batch stops for three reasons only: its cancel on the Activity screen, a cancel of one of its
 * photos there, and the account it started under leaving (a sign-out or a switch), which also stops
 * the background album downloads.
 */
@Singleton
class GalleryDownloads @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val offlineStore: OfflineStorageManager,
    private val transferCenter: TransferCenter,
    private val accountManager: AccountManager,
    @ApplicationContext private val context: Context,
) {
    sealed interface Outcome {
        /** The batch ran to its end; [progress] counts saved, skipped, failed and cancelled photos. */
        data class Finished(val progress: DownloadPhotosUseCase.Progress) : Outcome
        /** The batch broke off before its photos were counted. */
        data class Failed(val reason: String?) : Outcome
        /** No account was signed in, so nothing could be fetched. */
        data object NotSignedIn : Outcome
    }

    /** One save-to-device batch: its live [progress] (null until the first count) and its [outcome]. */
    class Batch internal constructor(
        val progress: StateFlow<DownloadPhotosUseCase.Progress?>,
        val outcome: Deferred<Outcome>,
    ) {
        /** The outcome, or null when the batch was stopped. Cancelling the caller only ends the wait. */
        suspend fun await(): Outcome? = outcome.awaitUnlessStopped()
    }

    /**
     * Saves [items] to the device: a cloud photo in an album lands in `DCIM/<AlbumName>/`, any other
     * in `DCIM/Camera`, and a photo already on the device is skipped. The Activity screen lists each
     * cloud photo with its own cancel, and the History tab gets the batch once it ends.
     */
    fun saveToDevice(items: List<GalleryItem>): Batch {
        val progress = MutableStateFlow<DownloadPhotosUseCase.Progress?>(null)
        val outcome = appScope.async {
            val userId = accountManager.getPrimaryUserId().first() ?: return@async Outcome.NotSignedIn
            stopOnAccountChange(userId) { runSave(userId, items, progress) }
        }
        return Batch(progress.asStateFlow(), outcome)
    }

    private suspend fun runSave(
        userId: UserId,
        items: List<GalleryItem>,
        progress: MutableStateFlow<DownloadPhotosUseCase.Progress?>,
    ): Outcome {
        val batch = currentCoroutineContext().job
        // One thumbnail URI per photo so the Activity screen lists them individually.
        val transfer = GalleryDownloadTransfer(transferCenter, context, items, items.map(::thumbUriFor)) {
            batch.cancel()
        }
        val savedUris = ConcurrentLinkedQueue<String>()
        val result = try {
            // Album membership routes an album photo into its album's folder. The repository caches
            // the map, so a repeat download does not fetch it again.
            val folders = if (items.any { it is GalleryItem.CloudOnly }) albumFolders(userId) else emptyMap()
            downloadPhotos.downloadGalleryItems(
                userId, items,
                folderName = "", // empty = a photo in no album goes straight into DCIM/Camera
                folderByLinkId = folders,
                onSaved = { savedUris.add(it) },
                cancels = transfer.cancels,
                onItemState = transfer::onItemState,
            ) { p ->
                progress.value = p
                transfer.onProgress(p)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "save to device failed: ${e.message}")
            return Outcome.Failed(e.message)
        } finally {
            transfer.finish()
        }
        transferCenter.log(TransferCenter.Kind.DOWNLOAD, result.done - result.failed, uris = savedUris.toList())
        return Outcome.Finished(result)
    }

    private suspend fun albumFolders(userId: UserId): Map<String, String> =
        runCatching { driveRepo.getAlbumMemberships(userId) }
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "getAlbumMemberships failed: ${it.message}")
                emptyMap()
            }
            .mapValues { (_, name) -> ProtonPhotosStorage.sanitize(name) }

    /**
     * Pins [photos] for offline viewing: the pins are written at once so the badges light up, each
     * full-res original then downloads into the offline store, and every pin whose photo did not
     * land (a failure, or the batch stopped first) is taken back with its blob, so a photo never
     * reads as offline with nothing behind it.
     */
    fun pinOffline(photos: List<CloudPhoto>): Deferred<PinResult> = appScope.async {
        val linkIds = photos.map { it.linkId }
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.OFFLINE_PIN_IDS] = (prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()) + linkIds
        }
        val userId = accountManager.getPrimaryUserId().first()
        val stored = mutableSetOf<String>()
        var failed = 0
        val savedPaths = mutableListOf<String>()
        val transferId = transferCenter.start(TransferCenter.Kind.OFFLINE, photos.size)
        var kept = 0
        try {
            if (userId == null) failed = photos.size
            else stopOnAccountChange(userId) {
                var attempted = 0
                for (photo in photos) {
                    try {
                        val file = driveRepo.downloadFullResPhoto(userId, photo)
                        val blob = offlineStore.store(photo.linkId, file.storedFile)
                        savedPaths += "file://${blob.absolutePath}"
                        stored += photo.linkId
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "offline pin failed: ${e.message}")
                        failed++
                    }
                    // Advance once per attempt so the Activity screen's row fills either way.
                    transferCenter.progress(transferId, ++attempted)
                }
            }
        } finally {
            transferCenter.finish(transferId)
            kept = withContext(NonCancellable) { settlePins(linkIds, stored) }
        }
        transferCenter.log(TransferCenter.Kind.OFFLINE, kept, uris = savedPaths)
        PinResult(pinned = kept, failed = failed)
    }

    /** How a pin batch ended: [pinned] photos have their blob in place, [failed] could not be
     *  downloaded. The rest were un-pinned while they downloaded, which is no failure. */
    data class PinResult(val pinned: Int, val failed: Int)

    /** Takes back the pins of [linkIds] that never got a blob, and drops any blob whose pin went
     *  away (an un-pin or a sign-out) while it downloaded, so the disk never drifts from the set.
     *  Returns how many photos are pinned with their blob in place. */
    private suspend fun settlePins(linkIds: List<String>, stored: Set<String>): Int {
        val missing = linkIds.toSet() - stored
        if (missing.isNotEmpty()) {
            context.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = (prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()) - missing
            }
            missing.forEach { offlineStore.delete(it) }
        }
        val finalPins = context.settingsDataStore.data.first()[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
        val (kept, unpinned) = stored.partition { it in finalPins }
        unpinned.forEach { offlineStore.delete(it) }
        return kept.size
    }

    /**
     * Hands an album's photos to the background album download. Only the hand-off runs here; the
     * download itself already outlives every screen. Resolves to false when no account is signed in.
     */
    fun enqueueAlbumDownload(albumLinkId: String, albumName: String, photoLinkIds: List<String>): Deferred<Boolean> =
        appScope.async {
            val userId = accountManager.getPrimaryUserId().first() ?: return@async false
            // enqueue() spills the id list to a cache file, so it runs off the main thread.
            withContext(Dispatchers.IO) {
                AlbumDownloadWorker.enqueue(context, albumLinkId, albumName, photoLinkIds, userId.id)
            }
            true
        }

    /** Runs [block], stopping it as soon as the signed-in account is no longer [userId]. */
    private suspend fun <T> stopOnAccountChange(userId: UserId, block: suspend () -> T): T {
        val work = currentCoroutineContext().job
        val watch = appScope.launch {
            accountManager.getPrimaryUserId().first { it != userId }
            work.cancel(CancellationException("the account changed"))
        }
        try {
            return block()
        } finally {
            watch.cancel()
        }
    }

    /** Thumbnail URI for one gallery item: the on-device file for local and synced photos, the cached
     *  cloud thumbnail (which may not exist yet) for cloud-only ones. */
    private fun thumbUriFor(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly ->
            "file://" + File(context.cacheDir, "thumbnails/thumb_${item.cloud.linkId}.jpg").absolutePath
    }

    private companion object {
        const val TAG = "GalleryDownloads"
    }
}

/**
 * The result of this deferred, or null when it was stopped. A caller that is itself cancelled still
 * gets its own cancellation, so leaving the screen ends the wait and nothing else.
 */
suspend fun <T> Deferred<T>.awaitUnlessStopped(): T? = try {
    await()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    if (isCancelled) null else throw e
}
