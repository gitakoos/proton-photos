/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.data.repository.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PerceptualHashDao
import eu.akoos.photos.data.db.entity.PerceptualHashEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.PerceptualHash
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PerceptualHashSched"

/**
 * Background filler for the persisted [PerceptualHashEntity] cache.
 *
 * The near-duplicate finder reads stored dHashes (see [eu.akoos.photos.presentation.duplicates
 * .DuplicateFinderViewModel]); this scheduler keeps that cache current. Given the live gallery
 * items, it finds the LocalOnly / CloudOnly candidates whose stored row is missing or stale
 * (freshness key drifted, or the algorithm version moved) and computes a dHash for each on a
 * small background pool, then upserts the result one at a time so the finder's Flow re-emits and
 * its grouping fills in progressively. Synced photos are skipped — they are a backed-up pair, not
 * two copies the user would want to dedupe.
 *
 * Decoding a bitmap touches disk (and, for cloud thumbnails, depends on the lazy decrypt cache),
 * so the work is bounded and paced — modelled on [eu.akoos.photos.data.repository
 * .LocalTagScanScheduler] — to never jank the gallery. The cache is rebuildable, so a dropped
 * task is harmless: the next [request] re-enqueues it.
 *
 * Cloud candidates whose decrypted thumbnail is not yet on disk are not hashed here; instead the
 * scheduler asks [DrivePhotoRepository.requestThumbnailDecrypt] to produce it (rate-limited to
 * [MAX_DECRYPT_REQUESTS_PER_PASS] per pass so a large library can't flood the crypto service) and
 * skips the item, so a later pass picks it up once warm.
 */
@Singleton
class PerceptualHashScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val perceptualHashDao: PerceptualHashDao,
    private val cloudRepo: DrivePhotoRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Concurrency bound on in-flight decode+hash jobs. A handful keeps the bitmap decodes off the
     *  scroll path without saturating disk I/O. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** One pending hash. [item] carries everything the decode needs; [freshness] is frozen at
     *  enqueue time so the upserted row records exactly what was hashed. */
    private class Task(val item: GalleryItem, val freshness: String)

    /** FIFO backlog drained by the worker pool. Guarded by [queueLock]; workers wake on
     *  [available]. */
    private val queue = ArrayDeque<Task>()
    private val queueLock = Mutex()

    /** Conflated wake signal — workers re-drain the whole queue on each wake, so collapsing many
     *  enqueues from one request into a single signal is correct. */
    private val available = Channel<Unit>(Channel.CONFLATED)

    /** Keys (uri / linkId) currently queued or running — the dedupe set, so repeated requests for
     *  the same not-yet-hashed item enqueue it once. */
    private val enqueued = ConcurrentHashMap.newKeySet<String>()

    init {
        repeat(WORKER_COUNT) { worker() }
    }

    /**
     * Compute the dHash for any of [items] whose stored row is missing or stale. Already-fresh
     * items are skipped via a single cache read. Returns immediately — hashing runs on the worker
     * pool and lands in the cache; the finder's Flow picks the hashes up as they arrive.
     */
    fun request(items: List<GalleryItem>, userId: UserId) {
        scope.launch {
            val cache = runCatching { perceptualHashDao.getAll().associateBy { it.key } }
                .getOrDefault(emptyMap())
            // Budget for cold-cloud-thumbnail decrypt requests in THIS pass. Without it a large
            // library asks the crypto service to decrypt every cold thumbnail at once, on every
            // pass; that sustained flood can make the :crypto process die repeatedly until the
            // client latches into the in-process fallback, where a residual native panic takes the
            // whole app down instead of just :crypto. Capping keeps the crypto load to a steady
            // trickle — the same handful of cold thumbnails are re-requested until they warm (the
            // decrypt scheduler dedups in-flight ones), then the window slides to the next batch.
            var decryptBudget = MAX_DECRYPT_REQUESTS_PER_PASS
            items.forEach { item ->
                val (key, freshness, _) = keyFor(item) ?: return@forEach
                if (!needsHash(cache[key], freshness)) return@forEach
                if (item is GalleryItem.CloudOnly && !cloudThumbExists(item.cloud.linkId)) {
                    if (decryptBudget > 0) {
                        decryptBudget--
                        runCatching { cloudRepo.requestThumbnailDecrypt(userId, item.cloud.linkId) }
                    }
                } else {
                    enqueue(item, freshness)
                }
            }
        }
    }

    /**
     * The stable key, freshness value, and cloud-flag for a hashable [item], or null for a Synced
     * item (excluded) or any item type we don't fingerprint. Device freshness is "<dateModified>_
     * <size>" so a file replaced in place recomputes; cloud freshness is the immutable linkId.
     */
    private fun keyFor(item: GalleryItem): Triple<String, String, Boolean>? = when (item) {
        is GalleryItem.LocalOnly -> {
            val l = item.local
            Triple(l.uri, "${l.dateModified}_${l.sizeBytes}", false)
        }
        is GalleryItem.CloudOnly -> {
            val linkId = item.cloud.linkId
            Triple(linkId, linkId, true)
        }
        is GalleryItem.Synced -> {
            // A Synced photo is ONE photo (device + cloud); fingerprint it from the local file so two
            // DIFFERENT synced photos that look alike still surface as near-duplicates of each other.
            val l = item.local
            Triple(l.uri, "${l.dateModified}_${l.sizeBytes}", false)
        }
    }

    /** True when no row exists for the key, its freshness key drifted, or the algorithm version
     *  moved since the row was written — any of which means the hash must be (re)computed. */
    private fun needsHash(cached: PerceptualHashEntity?, freshness: String): Boolean =
        cached == null ||
            cached.freshness != freshness ||
            cached.algoVersion != PerceptualHash.DHASH_ALGO_VERSION

    /** Enqueue a hash for an item whose source bitmap is ready: a local thumbnail, or a cloud
     *  thumbnail already decrypted to disk. Cold cloud thumbnails are warmed (rate-limited) by
     *  [request], so reaching here with one is unexpected — skip it rather than hash a missing file. */
    private fun enqueue(item: GalleryItem, freshness: String) {
        if (item is GalleryItem.CloudOnly && !cloudThumbExists(item.cloud.linkId)) return
        val key = item.stableId
        if (!enqueued.add(key)) return
        scope.launch {
            queueLock.withLock { queue.addLast(Task(item, freshness)) }
            available.trySend(Unit)
        }
    }

    private suspend fun takeNext(): Task? = queueLock.withLock {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    private fun worker() {
        scope.launch {
            for (signal in available) {
                while (true) {
                    val task = takeNext() ?: break
                    // Wake a sibling so a burst from one request is shared across the pool; a no-op
                    // once the queue drains (the woken worker just re-parks).
                    available.trySend(Unit)
                    val key = task.item.stableId
                    try {
                        semaphore.withPermit { hashAndStore(task) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "hash $key failed: ${e.message}")
                    } finally {
                        enqueued.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun hashAndStore(task: Task) {
        val item = task.item
        val (key, _, isCloud) = keyFor(item) ?: return
        val hash = when (item) {
            is GalleryItem.LocalOnly -> hashLocal(item.local)
            is GalleryItem.CloudOnly -> hashCloud(item.cloud.linkId)
            is GalleryItem.Synced -> hashLocal(item.local)
        } ?: return
        val row = PerceptualHashEntity(
            key = key,
            hash = hash,
            isCloud = isCloud,
            freshness = task.freshness,
            algoVersion = PerceptualHash.DHASH_ALGO_VERSION,
            computedAt = System.currentTimeMillis(),
        )
        runCatching { perceptualHashDao.upsertAll(listOf(row)) }
            .onFailure { Log.w(TAG, "upsert $key failed: ${it.message}") }
    }

    /** Decode a tiny local bitmap (MediaStore thumbnail on API 29+, sampled stream below), hash it,
     *  and recycle the bitmap at once so memory stays flat. */
    private fun hashLocal(local: LocalMediaItem): Long? = runCatching {
        val uri = Uri.parse(local.uri)
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(32, 32), null)
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        }
        bmp?.let {
            val hash = PerceptualHash.dHash(it)
            it.recycle()
            hash
        }
    }.getOrNull()

    /** Decode the decrypted cloud thumbnail off disk, hash it, and recycle at once. The caller has
     *  already confirmed the file exists (see [enqueue]). */
    private fun hashCloud(linkId: String): Long? = runCatching {
        val file = cloudThumbFile(linkId)
        BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
            val hash = PerceptualHash.dHash(bmp)
            bmp.recycle()
            hash
        }
    }.getOrNull()

    private fun cloudThumbExists(linkId: String): Boolean {
        val f = cloudThumbFile(linkId)
        return f.exists() && f.length() > 0L
    }

    private fun cloudThumbFile(linkId: String): File =
        File(File(context.cacheDir, "thumbnails"), "thumb_$linkId.jpg")

    private companion object {
        /** Worker pool size and concurrency bound. Bitmap decodes are light, so a small pool clears
         *  a large library's backlog without disturbing the scroll. */
        const val WORKER_COUNT = 3

        /** Cap on cold-cloud-thumbnail decrypt requests issued per [request] pass, so a large
         *  library can't flood the crypto service into the crash-prone in-process fallback. */
        const val MAX_DECRYPT_REQUESTS_PER_PASS = 8
    }
}
