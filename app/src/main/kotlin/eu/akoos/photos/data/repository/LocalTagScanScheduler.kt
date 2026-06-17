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

package eu.akoos.photos.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
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
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.PhotoTagDetector
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalTagScanSched"

/**
 * Background detector that fills the persisted [LocalTagEntity] cache for on-device media.
 *
 * The gallery reads category tags from the cache (see [LocalMediaRepositoryImpl]); this
 * scheduler is what keeps the cache current. Given the live MediaStore items, it finds the ones
 * whose cache entry is missing or stale (file replaced → dateModified / sizeBytes drift) and
 * runs [PhotoTagDetector.detectTags] for each on a small background pool, then upserts the
 * result. Detection can touch disk (the XMP prefix read for Motion Photo / Panorama), so the
 * work is bounded and paced — modelled on the thumbnail decrypt scheduler — to never jank the
 * gallery scroll. The cache is rebuildable, so a dropped scan is harmless: the next refresh
 * re-enqueues it.
 *
 * Ordering is newest-first (the items arrive already sorted newest-first), so the photos a user
 * is most likely to look at get accurate badges soonest.
 */
@Singleton
class LocalTagScanScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localTagDao: LocalTagDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Concurrency bound on in-flight detections. A handful keeps the XMP reads off the scroll
     *  path without saturating disk I/O. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** One pending detection. [item] carries everything [PhotoTagDetector] needs. */
    private class Task(val item: LocalMediaItem)

    /** FIFO backlog, drained by the worker pool. Guarded by [queueLock]; workers wake on
     *  [available]. Newest-first because callers pass items in newest-first order. */
    private val queue = ArrayDeque<Task>()
    private val queueLock = Mutex()

    /** Conflated wake signal — workers re-drain the whole queue on each wake, so collapsing
     *  many enqueues from one refresh into a single signal is correct. */
    private val available = Channel<Unit>(Channel.CONFLATED)

    /** URIs currently queued or running — the dedupe set, so a burst of refreshes for the same
     *  unscanned item enqueues it once. */
    private val enqueued = ConcurrentHashMap.newKeySet<String>()

    /** Snapshot of the most recently scheduled items, so [backfillAll] can re-walk the whole
     *  visible library without the caller re-supplying it. Local media is not in Room, so this
     *  is the scheduler's only handle on "every current item". */
    @Volatile private var lastItems: List<LocalMediaItem> = emptyList()

    /** One-shot guard for the detector-version check + cache wipe: it runs at most once per
     *  process, before the first cache read. [versionGate] serialises concurrent [schedule]
     *  callers so none reads the cache mid-wipe. */
    private val versionGate = Mutex()
    @Volatile private var versionEnsured = false

    init {
        repeat(WORKER_COUNT) { worker() }
    }

    /**
     * Detect tags for any of [items] whose cache entry is missing or stale, newest-first.
     * Already-fresh items are skipped via a single cache read. Returns immediately — detection
     * runs on the worker pool and lands in the cache; the next MediaStore scan picks the tags up.
     */
    fun schedule(items: List<LocalMediaItem>) {
        lastItems = items
        scope.launch {
            ensureDetectorVersion()
            val cache = runCatching { localTagDao.getAll().associateBy { it.uri } }
                .getOrDefault(emptyMap())
            items.forEach { item ->
                if (needsScan(item, cache[item.uri])) enqueue(item)
            }
        }
    }

    /**
     * Clear the persisted tag cache once if [PhotoTagDetector.DETECTOR_VERSION] has moved since
     * the cache was last written (tracked in a tiny prefs entry). A version bump means the
     * detection rules changed, so every cached row is potentially stale; wiping forces a full
     * re-detect on the scan that follows. A no-op on every run where the version is unchanged,
     * so after the first call it costs nothing.
     */
    private suspend fun ensureDetectorVersion() {
        if (versionEnsured) return
        versionGate.withLock {
            if (versionEnsured) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getInt(KEY_DETECTOR_VERSION, 0)
            if (stored != PhotoTagDetector.DETECTOR_VERSION) {
                runCatching { localTagDao.deleteAll() }
                    .onFailure { Log.w(TAG, "tag cache wipe failed: ${it.message}") }
                prefs.edit().putInt(KEY_DETECTOR_VERSION, PhotoTagDetector.DETECTOR_VERSION).apply()
                Log.i(TAG, "detector version $stored -> ${PhotoTagDetector.DETECTOR_VERSION}; tag cache cleared")
            }
            versionEnsured = true
        }
    }

    /**
     * Re-walk every item from the most recent [schedule] call and enqueue the ones still
     * missing or stale in the cache. A whole-library warm-up that costs nothing once the cache
     * is complete (every entry is fresh → nothing enqueues). Safe to call repeatedly.
     */
    fun backfillAll() {
        schedule(lastItems)
    }

    /** Drop queued work and the dedupe set. Called on sign-out so a stale scan can't run into a
     *  new session. In-flight detections finish naturally. */
    fun clear() {
        scope.launch { queueLock.withLock { queue.clear() } }
        enqueued.clear()
        lastItems = emptyList()
    }

    /** True when no cache row exists for the item, or the row's freshness key (dateModified +
     *  size) has drifted from the live item — i.e. the file was replaced in place. Mirrors the
     *  match [LocalMediaRepositoryImpl] uses when it applies cached tags. */
    private fun needsScan(item: LocalMediaItem, cached: LocalTagEntity?): Boolean =
        cached == null ||
            cached.dateModified != item.dateModified ||
            cached.sizeBytes != item.sizeBytes

    private fun enqueue(item: LocalMediaItem) {
        if (!enqueued.add(item.uri)) return
        scope.launch {
            queueLock.withLock { queue.addLast(Task(item)) }
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
                    // Wake a sibling so a burst from one refresh is shared across the pool; a
                    // no-op once the queue drains (the woken worker just re-parks).
                    available.trySend(Unit)
                    val uri = task.item.uri
                    try {
                        semaphore.withPermit { detectAndStore(task.item) }
                    } catch (e: Throwable) {
                        Log.w(TAG, "detect $uri failed: ${e.message}")
                    } finally {
                        enqueued.remove(uri)
                    }
                }
            }
        }
    }

    private suspend fun detectAndStore(item: LocalMediaItem) {
        val tagIds = PhotoTagDetector.detectTags(
            context = context,
            uri = Uri.parse(item.uri),
            mimeType = item.mimeType,
            displayName = item.displayName,
            sizeBytes = item.sizeBytes,
        )
        val entity = LocalTagEntity(
            uri = item.uri,
            dateModified = item.dateModified,
            sizeBytes = item.sizeBytes,
            tagsCsv = tagIds.joinToString(","),
            scannedAt = System.currentTimeMillis(),
        )
        runCatching { localTagDao.upsert(entity) }
            .onFailure { Log.w(TAG, "upsert ${item.uri} failed: ${it.message}") }
    }

    private companion object {
        /** Worker pool size and concurrency bound. XMP prefix reads are light, so a slightly
         *  wider pool clears a large library's backlog sooner without disturbing the scroll. */
        const val WORKER_COUNT = 4
        /** Tiny prefs file + key that persist the detector version the cache was built under. */
        const val PREFS_NAME = "local_tag_cache"
        const val KEY_DETECTOR_VERSION = "detector_version"
    }
}
