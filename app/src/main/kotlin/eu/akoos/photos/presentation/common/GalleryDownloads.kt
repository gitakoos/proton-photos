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
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.SaveBatchStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase.ItemResult
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.worker.AlbumDownloadWorker
import eu.akoos.photos.worker.PhotoSaveWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
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
 * handing an album to its background download. None of them runs on the screen's own scope, so
 * closing the screen right after the tap does not drop it: Search, a place, the viewer and an album
 * all close and are cleared while the photos are still coming down. The screen only waits for the
 * result, and stopping that wait leaves the download running.
 *
 * A save to the device goes further: the batch is written to disk and run as background work
 * ([PhotoSaveWorker]), so it also outlives the app process. When Android ends the process the work
 * runs again, removes any gallery row a save was still writing, and goes on with the photos that
 * were not done, never saving one twice.
 *
 * A batch stops for three reasons only: its cancel (the Activity screen, or the notification for a
 * save), a cancel of one of its photos on the Activity screen, and the account it started under
 * leaving (a sign-out or a switch), which also stops the background album downloads.
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

    /** How one run of a kept batch ended, for the background work that runs it. */
    enum class SaveRun {
        /** The batch is over and gone from disk. */
        FINISHED,
        /** The account it started under left, so it was dropped. */
        STOPPED,
        /** No such batch on disk: it finished or was dropped already. */
        MISSING,
    }

    /** The screen waiting on a batch this process started; a batch resumed after a restart has none. */
    private class Waiting(
        val progress: MutableStateFlow<DownloadPhotosUseCase.Progress?>,
        val outcome: CompletableDeferred<Outcome>,
        /** The picked photos as the screen had them, by linkId, for a photo the library does not list. */
        val photos: Map<String, CloudPhoto>,
    )

    private val waiting = ConcurrentHashMap<String, Waiting>()

    /** Where the batch files are read and written. A test runs them on its own clock. */
    internal var io: CoroutineDispatcher = Dispatchers.IO

    /** Starts the background work of a kept batch. A test runs the batch in place instead. */
    internal var startBatch: (batchId: String) -> Unit = { PhotoSaveWorker.enqueue(context, it) }

    /**
     * Saves [items] to the device: a cloud photo in an album lands in `DCIM/<AlbumName>/`, any other
     * in `DCIM/Camera`, and a photo already on the device is skipped. The Activity screen lists each
     * cloud photo with its own cancel, and the History tab gets the batch once it ends.
     */
    fun saveToDevice(items: List<GalleryItem>): Batch {
        val progress = MutableStateFlow<DownloadPhotosUseCase.Progress?>(null)
        val outcome = CompletableDeferred<Outcome>()
        appScope.launch {
            try {
                val userId = accountManager.getPrimaryUserId().first()
                if (userId == null) {
                    outcome.complete(Outcome.NotSignedIn)
                    return@launch
                }
                val cloud = items.filterIsInstance<GalleryItem.CloudOnly>().map { it.cloud }
                val onDevice = items.size - cloud.size
                if (cloud.isEmpty()) {
                    // Every photo is on the device already: nothing to fetch, nothing to keep.
                    transferCenter.log(TransferCenter.Kind.DOWNLOAD, onDevice)
                    outcome.complete(Outcome.Finished(DownloadPhotosUseCase.Progress(onDevice, onDevice, 0, skipped = onDevice)))
                    return@launch
                }
                val batchId = UUID.randomUUID().toString()
                withContext(io) {
                    SaveBatchStore(context.filesDir, batchId).create(
                        SaveBatchStore.Batch(userId.id, cloud.map { it.linkId }, onDevice),
                    )
                }
                waiting[batchId] = Waiting(progress, outcome, cloud.associateBy { it.linkId })
                startBatch(batchId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "save to device could not start: ${e.message}")
                outcome.complete(Outcome.Failed(e.message))
            }
        }
        return Batch(progress.asStateFlow(), outcome)
    }

    /**
     * Runs the kept batch [batchId], from wherever an earlier run stopped. Called by the background
     * work; [cancelBatch] stops that work, and [onProgress] gets the whole batch's count.
     */
    suspend fun runSaveBatch(
        batchId: String,
        cancelBatch: () -> Unit,
        onProgress: suspend (DownloadPhotosUseCase.Progress) -> Unit = {},
    ): SaveRun {
        val store = SaveBatchStore(context.filesDir, batchId)
        val outcome = try {
            val batch = withContext(io) { store.read() }
            if (batch == null) {
                discardSaveBatch(batchId)
                return SaveRun.MISSING
            }
            val userId = UserId(batch.userId)
            if (accountManager.getPrimaryUserId().first() != userId) {
                discardSaveBatch(batchId)
                return SaveRun.STOPPED
            }
            untilAccountChanges(userId) { runKeptBatch(userId, store, batch, cancelBatch, onProgress) }
                ?: run {
                    discardSaveBatch(batchId)
                    return SaveRun.STOPPED
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Anything the batch itself could not get past ends it, so neither the waiting screen nor
            // a folder on disk is left behind.
            Log.w(TAG, "save batch broke off: ${e.message}")
            Outcome.Failed(e.message)
        }
        store.delete()
        waiting.remove(batchId)?.outcome?.complete(outcome)
        return SaveRun.FINISHED
    }

    /** Drops the kept batch [batchId] and ends the wait of the screen that started it. */
    fun discardSaveBatch(batchId: String) {
        SaveBatchStore(context.filesDir, batchId).delete()
        waiting.remove(batchId)?.outcome?.cancel()
    }

    private suspend fun runKeptBatch(
        userId: UserId,
        store: SaveBatchStore,
        batch: SaveBatchStore.Batch,
        cancelBatch: () -> Unit,
        onProgress: suspend (DownloadPhotosUseCase.Progress) -> Unit,
    ): Outcome {
        withContext(io) { dropUnfinishedRows(store) }
        val earlier = withContext(io) { store.done() }.toMutableMap()
        val waiter = waiting[store.id]
        val photos = resolvePhotos(batch.linkIds.filter { it !in earlier }, waiter)
        // A photo neither the screen nor the library still has (a batch resumed after the process
        // ended, for a photo since deleted or one only a shared album listed) cannot be fetched.
        for (linkId in batch.linkIds) {
            if (linkId !in earlier && linkId !in photos) {
                store.markDone(linkId, ItemResult.FAILED)
                earlier[linkId] = ItemResult.FAILED
            }
        }
        val items = batch.linkIds.mapNotNull { photos[it] }.map { GalleryItem.CloudOnly(it) }
        // The whole batch's count: what earlier runs finished, the photos that were on the device
        // already, and this run's own count on top.
        fun before(r: ItemResult) = earlier.values.count { it == r }
        fun whole(p: DownloadPhotosUseCase.Progress): DownloadPhotosUseCase.Progress {
            val cancelled = before(ItemResult.CANCELLED) + p.cancelled
            return DownloadPhotosUseCase.Progress(
                done = batch.onDeviceCount + before(ItemResult.SAVED) + before(ItemResult.SKIPPED) +
                    before(ItemResult.FAILED) + p.done,
                total = batch.onDeviceCount + batch.linkIds.size - cancelled,
                failed = before(ItemResult.FAILED) + p.failed,
                skipped = batch.onDeviceCount + before(ItemResult.SKIPPED) + p.skipped,
                cancelled = cancelled,
            )
        }
        // One thumbnail URI per photo so the Activity screen lists them individually.
        val transfer = GalleryDownloadTransfer(transferCenter, context, items, items.map(::thumbUriFor), cancelBatch)
        val journal = object : DownloadPhotosUseCase.RowJournal {
            override fun opened(uri: Uri) = store.rowOpened(uri.toString())
            override fun settled(uri: Uri) = store.rowSettled(uri.toString())
        }
        val result = try {
            if (items.isEmpty()) {
                DownloadPhotosUseCase.Progress(0, 0, 0)
            } else {
                // Album membership routes an album photo into its album's folder. The repository
                // caches the map, so a repeat download does not fetch it again.
                downloadPhotos.downloadGalleryItems(
                    userId, items,
                    folderName = "", // empty = a photo in no album goes straight into DCIM/Camera
                    folderByLinkId = albumFolders(userId),
                    onSaved = store::markSaved,
                    cancels = transfer.cancels,
                    onItemState = transfer::onItemState,
                    onItemResult = store::markDone,
                    rowJournal = journal,
                ) { p ->
                    val all = whole(p)
                    waiter?.progress?.value = all
                    transfer.onProgress(all)
                    onProgress(all)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "save to device failed: ${e.message}")
            return Outcome.Failed(e.message)
        } finally {
            transfer.finish()
        }
        val all = whole(result)
        transferCenter.log(TransferCenter.Kind.DOWNLOAD, all.done - all.failed, uris = store.savedUris())
        return Outcome.Finished(all)
    }

    /** The photos of [linkIds]: as the screen picked them while it still waits, else as the library
     *  lists them. */
    private suspend fun resolvePhotos(linkIds: List<String>, waiter: Waiting?): Map<String, CloudPhoto> {
        val fromScreen = waiter?.photos.orEmpty().filterKeys { it in linkIds }
        val rest = linkIds.filter { it !in fromScreen }
        if (rest.isEmpty()) return fromScreen
        val fromLibrary = runCatching { driveRepo.observePhotosByLinkIds(rest).first() }
            .getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "could not read the batch's photos: ${it.message}")
                emptyList()
            }
        return fromScreen + fromLibrary.associateBy { it.linkId }
    }

    /**
     * Removes every gallery row an earlier run opened and never settled: the process ended while the
     * file was being written, so the row is no photo. A row that did get published is a whole photo
     * and stays. Rows are only ever pending from Android 10 on; before it a row cut off part-way has
     * a partly written file, so it goes too.
     */
    private fun dropUnfinishedRows(store: SaveBatchStore) {
        for (raw in store.openRows()) {
            val uri = Uri.parse(raw)
            if (isPublished(uri)) continue
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "could not remove an unfinished row: ${it.message}") }
        }
        store.clearRows()
    }

    /** True when [uri] reads back as a published row. A pending row reads back with IS_PENDING set,
     *  or not at all where the provider leaves pending rows out; either way it is not published. */
    private fun isPublished(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
                ?.use { it.moveToFirst() && it.getInt(0) == 0 }
        }.getOrNull() == true
    }

    /** Runs [block] and returns its result, or null once the signed-in account is no longer [userId],
     *  which stops the block. A cancel of the caller is passed on as it is. */
    private suspend fun <T> untilAccountChanges(userId: UserId, block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watch = launch {
            accountManager.getPrimaryUserId().first { it != userId }
            work.cancel(CancellationException("the account changed"))
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive()
            if (work.isCancelled) null else throw e
        } finally {
            watch.cancel()
        }
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
 * gets its own cancellation, so leaving the screen ends the wait and nothing else. [await] hands
 * back an already finished result without looking at the caller, so the caller is checked again.
 */
suspend fun <T> Deferred<T>.awaitUnlessStopped(): T? = try {
    await().also { currentCoroutineContext().ensureActive() }
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    if (isCancelled) null else throw e
}
