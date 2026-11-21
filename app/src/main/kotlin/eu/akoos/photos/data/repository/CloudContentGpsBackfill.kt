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
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.crypto.DecryptPriority
import eu.akoos.photos.crypto.DecryptPriorityContext
import eu.akoos.photos.data.crypto.parsePhotoLocation
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.GpsCheck
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.ContentEdgeReader
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.data.repository.drive.PhotoUnavailableException
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.util.ContentEdgeGps
import eu.akoos.photos.util.ContentGpsRead
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.isTransientApiError
import eu.akoos.photos.util.retryWithBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudContentGps"

/**
 * The second leg of the map's cloud GPS walk: a photo whose XAttr was read without a Location (an older
 * video upload, another client's upload) can still carry a fix in its own file, which the viewer shows once
 * the file is downloaded. This reads that fix from the file's first content block and, for an MP4 or MOV,
 * its last one ([ContentEdgeGps]), never the blocks in between, and stores it in `photo_location`.
 *
 * Every row ends in [GpsCheck.CONTENT_READ] once its blocks were read (fix or not) or it is known to carry
 * none, or in [GpsCheck.CONTENT_SKIPPED] when its file is not worth reading, so no photo is read twice; a
 * failure leaves it for a later pass, counted in its state, until [GpsCheck.CONTENT_GAVE_UP] after three; a
 * new revision starts it over. At most [MAX_READS_PER_PASS] files a pass, passes started in the background at
 * least [MIN_PASS_SPACING_MS] apart (the map's own run excepted), [WORKER_COUNT] reads at a time, with a pause
 * after each page that went to the network, only on an unmetered network (where the full-quality Wi-Fi-only
 * setting is met too), never on mobile data, and never
 * for a hidden or vaulted photo or one with a device copy (the device walk reads that one). A place found
 * here is stored in `photo_location` only and never written back to Drive.
 */
@Singleton
class CloudContentGpsBackfill @Inject constructor(
    private val photoListingDao: PhotoListingDao,
    private val photoLocationDao: PhotoLocationDao,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val photoLocationResolver: PhotoLocationResolver,
    private val downloadService: PhotoDownloadService,
    private val edgeReader: ContentEdgeReader,
    private val syncStateSnapshot: SyncStateSnapshot,
    private val networkObserver: NetworkObserver,
    @ApplicationContext private val context: Context,
) {
    private val semaphore = Semaphore(WORKER_COUNT)

    /** Where the last pass stopped, as (user id, linkId), so rows that keep failing never starve the rest. */
    @Volatile
    private var resumePoint: Pair<String, String>? = null

    /** Holds when the last pass began, across process starts, so every background trigger shares one spacing. */
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    @Volatile
    internal var now: () -> Long = { System.currentTimeMillis() }

    private sealed interface Outcome {
        class Located(val fix: PhotoLocationEntity) : Outcome
        object NoFix : Outcome
        object Skipped : Outcome
        class Retry(val transient: Boolean) : Outcome

        /** Left for the next pass because this one's read budget ran out. */
        object Deferred : Outcome
    }

    private class Pass {
        val readsLeft = AtomicInteger(MAX_READS_PER_PASS)
        val wentToNetwork = AtomicBoolean(false)
        val contentReads = AtomicInteger(0)
    }

    /**
     * Read the files of [userId]'s own-volume photos ([ownVolumeId]) whose XAttr gave no place, leaving out
     * [hiddenLinkIds]. [userAsked] marks the map's own run, which is not held to [MIN_PASS_SPACING_MS];
     * [awaitGate] holds each page for the device health gate.
     */
    suspend fun backfill(
        userId: UserId,
        ownVolumeId: String,
        hiddenLinkIds: Set<String>,
        userAsked: Boolean,
        awaitGate: suspend () -> Unit,
    ) {
        val startedAt = now()
        val last = prefs.getLong(KEY_LAST_PASS_AT, NEVER)
        // A clock set back since the last pass leaves a negative gap, which does not hold the pass back.
        if (!userAsked && last != NEVER && startedAt - last in 0 until MIN_PASS_SPACING_MS) {
            Log.d(TAG, "a pass ran ${(startedAt - last) / 1000}s ago, deferring")
            return
        }
        val sync = try {
            syncStateSnapshot.current(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync state unavailable, deferring: ${e.message}")
            return
        }
        val passOver = hiddenLinkIds + sync.hiddenCloudIds + sync.syncedCloudIds
        var after = resumePoint?.takeIf { it.first == userId.id }?.second.orEmpty()
        resumePoint = null
        var wrapped = after.isEmpty()
        var failuresInARow = 0
        var stamped = false
        val pass = Pass()
        while (pass.readsLeft.get() > 0) {
            awaitGate()
            if (!networkObserver.isUnmetered.value) {
                Log.d(TAG, "not on an unmetered network, deferring file reads")
                return
            }
            if (!stamped) {
                prefs.edit { putLong(KEY_LAST_PASS_AT, now()) }
                stamped = true
            }
            val page = try {
                photoListingDao.getXAttrReadWithoutLocation(userId.id, ownVolumeId, after, PAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "query failed: ${e.message}")
                return
            }
            if (page.isEmpty()) {
                if (wrapped) return
                wrapped = true
                after = ""
                continue
            }
            after = page.last().linkId
            val due = page.filter { it.linkId !in passOver && !downloadService.isDownloading(it.linkId) }
            pass.wentToNetwork.set(false)
            val readsBefore = pass.contentReads.get()

            val outcomes = ConcurrentHashMap<String, Outcome>()
            coroutineScope {
                due.forEach { row ->
                    launch {
                        outcomes[row.linkId] = semaphore.withPermit {
                            withContext(DecryptPriorityContext(DecryptPriority.BACKGROUND)) { readOne(userId, row, pass) }
                        }
                    }
                }
            }
            failuresInARow = failuresAfter(failuresInARow, outcomes.values)
            val outage = failuresInARow >= MAX_FAILURES_IN_A_ROW
            // Network failures count against a photo only on a page where another file was read.
            val networkWorked = pass.contentReads.get() > readsBefore
            store(outcomes, due.associate { it.linkId to it.gpsChecked }, countNetworkFailures = !outage && networkWorked)
            // Failures piling up across pages mean an outage; a later pass retries those photos.
            if (outage) {
                Log.d(TAG, "$failuresInARow passing failures in a row, stopping this pass")
                resumePoint = userId.id to after
                return
            }
            if (page.size < PAGE) {
                if (wrapped) return
                wrapped = true
                after = ""
            }
            if (pass.wentToNetwork.get()) delay(PAGE_DELAY_MS)
        }
        resumePoint = userId.id to after
    }

    /** Passing failures counted on from [soFar] while pages fail more than they settle, else back to none. */
    private fun failuresAfter(soFar: Int, outcomes: Collection<Outcome>): Int {
        val settled = outcomes.count { it !is Outcome.Retry && it !is Outcome.Deferred }
        val failed = outcomes.count { it is Outcome.Retry }
        return if (failed > settled) soFar + failed else 0
    }

    /** Persist one page: the fixes found, every row that will not be read again, and each failure counted
     *  against its row ([states] holds each row's state before the page). */
    private suspend fun store(outcomes: Map<String, Outcome>, states: Map<String, Int>, countNetworkFailures: Boolean) {
        val located = outcomes.values.filterIsInstance<Outcome.Located>().map { it.fix }
        if (located.isNotEmpty()) {
            runCatching { photoLocationDao.upsert(located) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "upsert ${located.size} location(s) failed: ${it.message}")
                }
        }
        val read = outcomes.filterValues { it is Outcome.Located || it is Outcome.NoFix }.keys.toList()
        val skipped = outcomes.filterValues { it is Outcome.Skipped }.keys.toList()
        settle(read, GpsCheck.CONTENT_READ)
        settle(skipped, GpsCheck.CONTENT_SKIPPED)
        val counted = outcomes.filterValues { it is Outcome.Retry && (countNetworkFailures || !it.transient) }.keys
        counted.groupBy { stateAfterFailure(states[it] ?: GpsCheck.XATTR_READ) }.forEach { (state, ids) -> settle(ids, state) }
        Log.d(TAG, "page: rows=${outcomes.size} located=${located.size} read=${read.size} skipped=${skipped.size} failed=${counted.size}")
    }

    private suspend fun settle(linkIds: List<String>, state: Int) {
        if (linkIds.isEmpty()) return
        runCatching { photoListingDao.setGpsCheck(linkIds, state) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "marking ${linkIds.size} row(s) failed: ${it.message}")
            }
    }

    private suspend fun readOne(userId: UserId, row: PhotoListingEntity, pass: Pass): Outcome = try {
        val armored = row.encXAttr ?: run {
            pass.wentToNetwork.set(true)
            retryWithBackoff(maxAttempts = FETCH_MAX_ATTEMPTS, baseMs = FETCH_RETRY_BASE_MS) {
                linkDetailHelpers.fetchRevisionXAttrOrThrow(userId, row.volumeId, row.shareId, row.linkId, row.revisionId)
            }
        }
        val json = armored?.let { photoLocationResolver.decryptXAttr(userId, row, it) }
        val fromXAttr = json?.let { parsePhotoLocation(it) }
        if (fromXAttr != null) {
            Outcome.Located(fixOf(userId, row, fromXAttr))
        } else {
            readFromContent(userId, row, ContentEdgeGps.plan(row.mimeType, armored != null, json), pass)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is PhotoUnavailableException || isRevisionGoneForGood(e)) {
            Log.d(TAG, "${row.linkId} cannot be read, set aside: ${e.message}")
            Outcome.NoFix
        } else {
            Log.d(TAG, "${row.linkId} read failed, a later pass retries: ${e.message}")
            Outcome.Retry(transient = isTransientApiError(e))
        }
    }

    private suspend fun readFromContent(userId: UserId, row: PhotoListingEntity, plan: ContentGpsRead, pass: Pass): Outcome {
        if (plan == ContentGpsRead.NONE) return Outcome.Skipped
        if (!networkObserver.isUnmetered.value || pass.readsLeft.getAndDecrement() <= 0) return Outcome.Deferred
        pass.wentToNetwork.set(true)
        val photo = row.toCloudPhoto()
        val atBothEnds = plan == ContentGpsRead.FIRST_AND_LAST
        val edges = edgeReader.readContentEdges(userId, photo, withFirst = true, tailBlocks = if (atBothEnds) 1 else 0)
        pass.contentReads.incrementAndGet()
        val first = edges.first ?: return Outcome.NoFix
        val tail = edges.last
        var fix = ContentEdgeGps.read(row.mimeType, first, tail)
        // A short last block may hold only the end of a movie box that starts in the block before it.
        if (fix == null && atBothEnds && tail != null && edges.blockCount > 2 && tail.size < SHORT_TAIL_BYTES) {
            val longerTail = edgeReader.readContentEdges(userId, photo, withFirst = false, tailBlocks = 2).last
            fix = ContentEdgeGps.read(row.mimeType, first, longerTail)
        }
        return fix?.let { Outcome.Located(fixOf(userId, row, it)) } ?: Outcome.NoFix
    }

    private fun fixOf(userId: UserId, row: PhotoListingEntity, fix: Pair<Double, Double>) =
        PhotoLocationEntity(id = row.linkId, userId = userId.id, latitude = fix.first, longitude = fix.second)

    private fun PhotoListingEntity.toCloudPhoto() = CloudPhoto(
        linkId = linkId,
        shareId = shareId,
        volumeId = volumeId,
        captureTime = captureTime,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        thumbnailUrl = null,
        revisionId = revisionId,
    )

    private companion object {
        /** Each read holds up to three 4 MiB blocks in memory, so only a couple run at once. */
        const val WORKER_COUNT = 2

        /** Files one pass reads at most; the rest wait for the next trigger. */
        const val MAX_READS_PER_PASS = 100

        /** Least time between two passes a background trigger starts. */
        const val MIN_PASS_SPACING_MS = 30 * 60 * 1000L
        const val PREFS_NAME = "cloud_content_gps"
        const val KEY_LAST_PASS_AT = "last_pass_at"
        const val NEVER = -1L

        /** Passing failures, counted across pages, after which a pass gives up until the next trigger. */
        const val MAX_FAILURES_IN_A_ROW = 6

        const val SHORT_TAIL_BYTES = 2 * 1024 * 1024

        const val PAGE = 50
        const val PAGE_DELAY_MS = 400L
        const val FETCH_MAX_ATTEMPTS = 3
        const val FETCH_RETRY_BASE_MS = 600L
    }
}

/** The state a row moves to after one more failed read: counted twice, then given up. */
internal fun stateAfterFailure(state: Int): Int = when (state) {
    GpsCheck.XATTR_READ -> GpsCheck.CONTENT_FAILED_ONCE
    GpsCheck.CONTENT_FAILED_ONCE -> GpsCheck.CONTENT_FAILED_TWICE
    else -> GpsCheck.CONTENT_GAVE_UP
}
