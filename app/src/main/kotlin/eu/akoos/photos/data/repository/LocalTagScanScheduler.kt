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

package eu.akoos.photos.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.util.PerfDiagnostics
import eu.akoos.photos.util.PhotoTagDetector
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalTagScanSched"

/**
 * Background detector that fills the persisted [LocalTagEntity] cache for on-device media.
 *
 * The gallery reads category tags from the cache (see [LocalMediaRepositoryImpl]); this scheduler
 * keeps the cache current. Each device scan hands over its items and the cache rows it read; the
 * photos whose row is missing or stale (the file was replaced in place, so dateModified or size
 * drifted) become one [TagBatch], which a small fixed pool runs through
 * [PhotoTagDetector.detectTags] a chunk at a time, upserting each result. Detection can touch disk
 * (the XMP prefix read for Motion Photo / Panorama), so the pool stays small enough to never jank
 * the gallery scroll. The cache is rebuildable, so a dropped batch is harmless: the next scan plans
 * whatever is still missing.
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

    /** One-shot guard for the detector-version check + cache wipe: it runs at most once per
     *  process, before the first plan. [versionGate] serialises concurrent [schedule] callers so
     *  none plans mid-wipe. */
    private val versionGate = Mutex()
    @Volatile private var versionEnsured = false

    /** The rows stored since the last plan, capped at [WRITE_LOG_CAP] with the oldest dropped first,
     *  and guarded by [writeLogLock]. [schedule] says what a plan needs them for. */
    private val writeLogLock = Any()
    private var writtenSincePlan = newWriteLog()

    private val runner = TagBatchRunner(
        scope = scope,
        workerCount = WORKER_COUNT,
        chunkSize = CHUNK_SIZE,
        onStart = { photos -> PerfDiagnostics.mark("tagscan:start", photos) },
        onEnd = { tagged -> PerfDiagnostics.mark("tagscan:end", tagged) },
        tag = { target -> detectAndStore(target) },
    )

    /**
     * Plan the tagging for one device scan. [items] is everything the scan found, newest-first, and
     * [tagCache] the cache rows it read as it began. The photos with no fresh row become the pending
     * batch and replace the one an earlier scan left, whose untagged photos this plan already holds.
     * Returns once the batch is queued; the detections land in the cache and the next scan picks the
     * tags up.
     *
     * A photo stored while the scan ran is fresh in the table yet missing from [tagCache], so the
     * rows stored since the previous plan count ahead of it. Scans call this one at a time, so those
     * are the only rows [tagCache] can lack.
     */
    suspend fun schedule(items: List<LocalMediaItem>, tagCache: Map<String, LocalTagEntity>) {
        // The new version is recorded after the wipe, so the wipe runs to its end even if the scan
        // is cancelled.
        val wiped = withContext(NonCancellable) { ensureDetectorVersion() }
        // A wipe leaves tagCache out of date, and a failed read also hands over an empty map, so
        // either is read afresh. After a wipe, and on a fresh install, the table is all but empty.
        val cache = if (wiped || tagCache.isEmpty()) readCache() else tagCache
        runner.submit(planTagBatch(items, cache, takeWriteLog()))
    }

    /**
     * Discard every cached detection once if [PhotoTagDetector.DETECTOR_VERSION] has moved since
     * the cache was last written (tracked in a tiny prefs entry). A version bump means the
     * detection rules changed, so every cached verdict is potentially stale; clearing forces a full
     * re-detect on the scan that follows. A no-op on every run where the version is unchanged,
     * so after the first call it costs nothing. Returns true when this call ran the wipe.
     *
     * [LocalTagDao.clearDetections] and not a whole-table delete, because a row can also carry the
     * categories the user picked for that file. Those are not the detector's to discard: a change to
     * its rules says nothing about a choice a person made, and no re-scan could recover one.
     */
    private suspend fun ensureDetectorVersion(): Boolean {
        if (versionEnsured) return false
        return versionGate.withLock {
            if (versionEnsured) return@withLock false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getInt(KEY_DETECTOR_VERSION, 0)
            val moved = stored != PhotoTagDetector.DETECTOR_VERSION
            if (moved) {
                runCatching { localTagDao.clearDetections() }
                    .onFailure { Log.w(TAG, "tag cache wipe failed: ${it.message}") }
                prefs.edit().putInt(KEY_DETECTOR_VERSION, PhotoTagDetector.DETECTOR_VERSION).apply()
                Log.i(TAG, "detector version $stored -> ${PhotoTagDetector.DETECTOR_VERSION}; detections cleared")
            }
            versionEnsured = true
            moved
        }
    }

    private suspend fun readCache(): Map<String, LocalTagEntity> =
        runCatching { localTagDao.getAll().associateBy { it.uri } }.getOrDefault(emptyMap())

    /** Drop the pending batch, so a stale scan can't run into a new session. The photos in flight
     *  finish naturally. */
    fun clear() {
        runner.clear()
    }

    private suspend fun detectAndStore(target: TagTarget): Boolean {
        val tagIds = PhotoTagDetector.detectTags(
            context = context,
            uri = Uri.parse(target.uri),
            mimeType = target.mimeType,
            displayName = target.displayName,
            sizeBytes = target.sizeBytes,
        )
        // userTagsCsv is deliberately absent: upsertDetection writes the detection columns only, so a
        // re-scan of a file the user has categorised keeps their choice.
        val entity = LocalTagEntity(
            uri = target.uri,
            dateModified = target.dateModified,
            sizeBytes = target.sizeBytes,
            tagsCsv = tagIds.joinToString(","),
            scannedAt = System.currentTimeMillis(),
        )
        return runCatching { localTagDao.upsertDetection(entity) }
            .onSuccess { logWrite(entity) }
            .onFailure { Log.w(TAG, "upsert ${target.uri} failed: ${it.message}") }
            .isSuccess
    }

    private fun logWrite(row: LocalTagEntity) {
        synchronized(writeLogLock) { writtenSincePlan[row.uri] = row }
    }

    /** The rows stored since the last plan, with a new log begun for the next one. */
    private fun takeWriteLog(): Map<String, LocalTagEntity> = synchronized(writeLogLock) {
        writtenSincePlan.also { writtenSincePlan = newWriteLog() }
    }

    private fun newWriteLog(): LinkedHashMap<String, LocalTagEntity> =
        object : LinkedHashMap<String, LocalTagEntity>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LocalTagEntity>?): Boolean =
                size > WRITE_LOG_CAP
        }

    private companion object {
        /** Worker pool size and concurrency bound. XMP prefix reads are light, so a slightly
         *  wider pool clears a large library's backlog sooner without disturbing the scroll. */
        const val WORKER_COUNT = 4
        /** Photos a worker claims from the batch at a time. */
        const val CHUNK_SIZE = 32
        /** Rows the write log keeps for the next plan. A row that falls off costs one repeat
         *  detection of its photo, nothing more. */
        const val WRITE_LOG_CAP = 8_192
        /** Tiny prefs file + key that persist the detector version the cache was built under. */
        const val PREFS_NAME = "local_tag_cache"
        const val KEY_DETECTOR_VERSION = "detector_version"
    }
}

/** True when no cache row exists for the item, or the row's freshness key (dateModified + size) has
 *  drifted from the live item, i.e. the file was replaced in place. Mirrors the match
 *  [LocalMediaRepositoryImpl] uses when it applies cached tags. */
private fun needsScan(item: LocalMediaItem, cached: LocalTagEntity?): Boolean =
    cached == null ||
        cached.dateModified != item.dateModified ||
        cached.sizeBytes != item.sizeBytes

/**
 * The photos in [items] with no fresh detection, in the order given. A row in [written], stored
 * after [cache] was read, counts ahead of the row [cache] holds for the same photo.
 */
internal fun planTagBatch(
    items: List<LocalMediaItem>,
    cache: Map<String, LocalTagEntity>,
    written: Map<String, LocalTagEntity> = emptyMap(),
): TagBatch {
    val stale = BitSet(items.size)
    items.forEachIndexed { index, item ->
        if (needsScan(item, written[item.uri] ?: cache[item.uri])) stale.set(index)
    }
    val count = stale.cardinality()
    val uris = arrayOfNulls<String>(count)
    val displayNames = arrayOfNulls<String>(count)
    val mimeTypes = arrayOfNulls<String>(count)
    val sizes = LongArray(count)
    val modifiedTimes = LongArray(count)
    // One copy of each type string serves every photo of that type.
    val sharedMimeTypes = HashMap<String, String>()
    var slot = 0
    items.forEachIndexed { index, item ->
        if (stale[index]) {
            uris[slot] = item.uri
            displayNames[slot] = item.displayName
            mimeTypes[slot] = sharedMimeTypes.getOrPut(item.mimeType) { item.mimeType }
            sizes[slot] = item.sizeBytes
            modifiedTimes[slot] = item.dateModified
            slot++
        }
    }
    return TagBatch(uris, displayNames, mimeTypes, sizes, modifiedTimes)
}

/**
 * The photos a plan found to tag, as the fields detection and the cache row read. Parallel arrays
 * rather than [LocalMediaItem]s, so a pending batch pins no scan's item list, and [take] lets go of
 * a photo's strings as it hands the photo out.
 */
internal class TagBatch(
    private val uris: Array<String?>,
    private val displayNames: Array<String?>,
    private val mimeTypes: Array<String?>,
    private val sizes: LongArray,
    private val modifiedTimes: LongArray,
) {
    val size: Int get() = uris.size

    /** The photo at [index], or null once it has been handed out. */
    fun take(index: Int): TagTarget? {
        val uri = uris[index] ?: return null
        val target = TagTarget(
            uri = uri,
            displayName = displayNames[index].orEmpty(),
            mimeType = mimeTypes[index].orEmpty(),
            sizeBytes = sizes[index],
            dateModified = modifiedTimes[index],
        )
        uris[index] = null
        displayNames[index] = null
        mimeTypes[index] = null
        return target
    }
}

/** One photo to tag: what [PhotoTagDetector.detectTags] reads and the freshness key its row stores. */
internal class TagTarget(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModified: Long,
)

/**
 * Runs one [TagBatch] at a time on [workerCount] workers. Each worker claims [chunkSize] photos at
 * once and hands them to [tag] in turn, which answers whether the photo was stored. [submit]
 * replaces the batch, finished or not: the newer plan already holds whatever the older one left
 * that still needs tagging, so a worker drops the older one after the photo it is on. [onStart]
 * reports a batch's photos as it is queued, and [onEnd] the photos it stored, once, when it
 * completes, is replaced or is cleared.
 */
internal class TagBatchRunner(
    scope: CoroutineScope,
    workerCount: Int,
    private val chunkSize: Int,
    private val onStart: (photos: Int) -> Unit,
    private val onEnd: (tagged: Int) -> Unit,
    private val tag: suspend (TagTarget) -> Boolean,
) {
    private class Run(val batch: TagBatch) {
        val claimed = AtomicInteger()
        val finished = AtomicInteger()
        val tagged = AtomicInteger()
        val ended = AtomicBoolean()
    }

    /** The pending batch; written only under this runner's lock. */
    @Volatile private var current: Run? = null

    /** Conflated wake signal. A worker wakes a sibling for every chunk it claims, so one signal
     *  spreads across the pool while chunks remain. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        require(workerCount > 0 && chunkSize > 0)
        repeat(workerCount) { scope.launch { work() } }
    }

    /** Queue [batch] in place of the pending one, which ends here. An empty batch queues nothing. */
    @Synchronized
    fun submit(batch: TagBatch) {
        current?.let(::end)
        if (batch.size == 0) {
            current = null
            return
        }
        current = Run(batch)
        onStart(batch.size)
        wake.trySend(Unit)
    }

    /** Drop the pending batch. The photos in flight finish. */
    @Synchronized
    fun clear() {
        current?.let(::end)
        current = null
    }

    private suspend fun work() {
        while (true) {
            wake.receive()
            while (true) {
                val run = current ?: break
                val from = run.claimed.getAndAdd(chunkSize)
                if (from >= run.batch.size) break
                wake.trySend(Unit)
                val to = minOf(from + chunkSize, run.batch.size)
                for (index in from until to) {
                    if (current !== run) break
                    val target = run.batch.take(index) ?: continue
                    if (tagOne(target)) run.tagged.incrementAndGet()
                }
                if (run.finished.addAndGet(to - from) == run.batch.size) complete(run)
            }
        }
    }

    private suspend fun tagOne(target: TagTarget): Boolean = try {
        tag(target)
    } catch (e: Throwable) {
        // Only this worker's own cancellation stops it; any other failure costs the one photo.
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "detect ${target.uri} failed: ${e.message}")
        false
    }

    private fun complete(run: Run) = synchronized(this) {
        if (current === run) current = null
        end(run)
    }

    private fun end(run: Run) {
        if (run.ended.compareAndSet(false, true)) onEnd(run.tagged.get())
    }
}
