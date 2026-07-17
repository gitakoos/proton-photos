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

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.crypto.parsePhotoDuration
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.AlbumCryptoChain
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.util.isTransientApiError
import eu.akoos.photos.util.retryWithBackoff
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VideoDurationBackfill"

/**
 * Fills `photo_listing.durationMs` with the length of every CLOUD video, so the grid can show a
 * duration pill on a video cell without downloading the file or probing it with a retriever. The
 * duration lives in the encrypted xAttr Media.Duration block (in seconds); upload writes it, and the
 * listing walk already caches the armored xAttr in `photo_listing.encXAttr`. This walks the video rows
 * whose duration is still unknown ([PhotoListingDao.getVideosMissingDuration]), decrypts the xAttr off
 * the read path, recovers the duration via [parsePhotoDuration] (converting seconds to milliseconds),
 * and writes it back per linkId.
 *
 * This deliberately mirrors [CloudGpsBackfillScheduler] one-for-one, same bounded paging, the same
 * two small [Semaphore]s pacing revision fetches and xAttr decrypts, the same 429 / 5xx-aware
 * [retryWithBackoff] on the per-photo fetch, the same owner-side parent-key resolution with a local
 * cache, and the same single [backfilling] guard collapsing overlapping triggers. The one structural
 * difference is that this walk needs no "checked" flag: a row that gets a duration written falls out of
 * [PhotoListingDao.getVideosMissingDuration] on the next page (`durationMs IS NULL` no longer holds), so
 * the query itself is the bound. A row whose fetch fails or whose xAttr carries no duration simply stays
 * NULL for a later run, and the zero-progress guard turns a sustained outage into a clean pause rather
 * than a spin. A row whose parent key can't be resolved here (e.g. a shared-with-me album) is skipped,
 * like any other per-row failure.
 */
@Singleton
class VideoDurationBackfillScheduler @Inject constructor(
    private val cryptoServiceClient: CryptoServiceClient,
    private val photoListingDao: PhotoListingDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumCryptoChain: AlbumCryptoChain,
) {
    /** Concurrency bound on in-flight XAttr decrypts, a handful keeps JNI / GC pressure low. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /** Tighter bound on in-flight revision fetches, so the backfill never crowds foreground traffic. */
    private val fetchSemaphore = Semaphore(FETCH_WORKER_COUNT)

    /** One walk at a time, a second trigger while one is running is a no-op, not a double pass. */
    private val backfilling = AtomicBoolean(false)

    /** parentLinkId → decrypted parent key bytes, so videos sharing a parent skip the re-decrypt. */
    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Decrypt + persist the duration of every cloud video whose length isn't known yet for [userId].
     * Walks the missing-duration rows in bounded pages until none remain, fetches paced by
     * [fetchSemaphore] and decrypts by [semaphore], with a short [PAGE_DELAY_MS] gap between pages.
     * Stops a pass early when a page resolves no new revisions (a sustained outage), leaving those rows
     * for a later run. A no-op once every video row carries a duration (nothing left with
     * `durationMs IS NULL`), and self-collapsing across overlapping calls, so a screen can fire it freely.
     */
    suspend fun backfillAll(userId: UserId) {
        if (!backfilling.compareAndSet(false, true)) return
        try {
            while (true) {
                val batch = runCatching { photoListingDao.getVideosMissingDuration(userId.id, PAGE) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "query failed: ${e.message}"); break
                    }
                if (batch.isEmpty()) break

                val resolved = resolveXAttrs(userId, batch)

                // A page whose every fetch failed resolves no NEW revision: nothing to write, so no row
                // can leave the missing-duration set and the walk can't advance. Stop this pass rather
                // than spin, the rows stay durationMs=NULL for a later run. (Cached rows count as
                // resolved, so a page that's all cache still makes progress.)
                if (resolved.ids.isEmpty()) {
                    Log.d(TAG, "page resolved 0 revisions, pausing walk, will retry on a later run")
                    break
                }

                val durations = ConcurrentHashMap<String, Long>()
                coroutineScope {
                    batch.forEach { row ->
                        if (row.linkId !in resolved.ids) return@forEach
                        launch {
                            try {
                                semaphore.withPermit {
                                    durationFor(userId, row, resolved.byLinkId[row.linkId])?.let {
                                        durations[row.linkId] = it
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.w(TAG, "duration decrypt ${row.linkId} failed: ${e.message}")
                            }
                        }
                    }
                }

                Log.d(TAG, "page: walked=${batch.size} xattrResolved=${resolved.ids.size} durations=${durations.size}")
                // Persist each recovered duration per-linkId. A row that got a value drops out of the
                // next page's query; a row that resolved but carried no duration (unusual for a video)
                // stays NULL, and the terminating LIMIT-below-PAGE / all-cached guards keep the walk from
                // looping on such a stuck tail.
                durations.forEach { (linkId, ms) ->
                    runCatching { photoListingDao.updateDurationMs(linkId, ms) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "updateDurationMs $linkId failed: ${it.message}")
                        }
                }

                // A short page ends the walk; a full page with zero writes would re-query the same rows,
                // so bail there too and let a later run retry once the transient clears. A full page whose
                // videos ALL carry an xAttr with no parseable Media.Duration also lands here: those rows
                // stay durationMs=NULL and getVideosMissingDuration would keep returning them, so ending the
                // pass and deferring the rest to a future trigger is the bound that stops an endless re-walk,
                // not a lost row.
                if (batch.size < PAGE || durations.isEmpty()) break

                // Trickle a large library: a short gap between pages keeps the backfill gentle on the
                // Drive API and off the foreground's back instead of bursting page after page.
                delay(PAGE_DELAY_MS)
            }
        } finally {
            backfilling.set(false)
        }
    }

    /**
     * The outcome of resolving a page's XAttrs: [byLinkId] holds every armored XAttr actually in hand
     * (cached or fetched), while [ids] is the set of linkIds whose revision was successfully READ, a
     * superset of [byLinkId]'s keys, since a revision can be read yet carry no XAttr. Only [ids] advances
     * the walk; rows missing from it had their fetch fail and are retried on a later run.
     */
    private class ResolvedXAttrs(
        val byLinkId: Map<String, String>,
        val ids: Set<String>,
    )

    /**
     * Resolve one page's XAttrs. Rows with a cached [PhotoListingEntity.encXAttr] contribute it directly
     * (no network) and count as resolved. The rest have their XAttr fetched per photo from the revision
     * endpoint via [LinkDetailHelpers.fetchRevisionXAttrOrThrow], the link-metadata endpoints omit it.
     * Each fetch is gated through [fetchSemaphore] and wrapped in the shared 429 / 5xx-aware
     * [retryWithBackoff], so a transient blip backs off rather than spamming. A fetch that still fails is
     * left out of [ResolvedXAttrs.ids] for a later run; a successful fetch with no XAttr is resolved but
     * absent from [byLinkId], so [durationFor] simply yields nothing for it.
     */
    private suspend fun resolveXAttrs(
        userId: UserId,
        batch: List<PhotoListingEntity>,
    ): ResolvedXAttrs {
        val map = ConcurrentHashMap<String, String>(batch.size)
        val resolvedIds = ConcurrentHashMap.newKeySet<String>()
        val missing = ArrayList<PhotoListingEntity>()
        for (row in batch) {
            val cached = row.encXAttr
            if (cached != null) {
                map[row.linkId] = cached
                resolvedIds.add(row.linkId)
            } else {
                missing.add(row)
            }
        }
        if (missing.isEmpty()) return ResolvedXAttrs(map, resolvedIds)

        coroutineScope {
            missing.forEach { row ->
                launch {
                    try {
                        val xAttr = fetchSemaphore.withPermit {
                            retryWithBackoff(maxAttempts = FETCH_MAX_ATTEMPTS, baseMs = FETCH_RETRY_BASE_MS) {
                                linkDetailHelpers.fetchRevisionXAttrOrThrow(
                                    userId, row.volumeId, row.shareId, row.linkId, row.revisionId,
                                )
                            }
                        }
                        resolvedIds.add(row.linkId)
                        if (xAttr != null) map[row.linkId] = xAttr
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isTransientApiError(e)) {
                            Log.d(TAG, "fetch XAttr ${row.linkId} transient, will retry later: ${e.message}")
                        } else {
                            Log.w(TAG, "fetch XAttr ${row.linkId} failed: ${e.message}")
                        }
                    }
                }
            }
        }
        Log.d(TAG, "resolveXAttrs via revision: missing=${missing.size} resolved=${resolvedIds.size} withXAttr=${map.size}")
        return ResolvedXAttrs(map, resolvedIds)
    }

    /** Decrypt one row's XAttr and return its duration in milliseconds, or null when it carries no
     *  duration / can't be resolved. [armoredXAttr] is the row's cached XAttr or, for pre-encXAttr rows,
     *  the value fetched in [resolveXAttrs]. The parent key resolution mirrors the GPS backfill's
     *  owner-side branches. */
    private suspend fun durationFor(
        userId: UserId,
        row: PhotoListingEntity,
        armoredXAttr: String?,
    ): Long? {
        val encXAttr = armoredXAttr ?: return null
        val encNodeKey = row.encNodeKey ?: return null
        val encNodePass = row.encNodePassphrase ?: return null
        val parentLinkId = row.parentLinkId ?: return null
        val parentKey = getParentKeyBytes(userId, parentLinkId, row.volumeId) ?: return null
        val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        val json = cryptoServiceClient.decryptXAttr(encXAttr, nodeKeyBytes) ?: return null
        return parsePhotoDuration(json)
    }

    /**
     * Decrypted node-key bytes for [parentLinkId], replicating the owner-side resolution in
     * [CloudGpsBackfillScheduler]:
     *   • Photos root link → [PhotosShareService.getRootLinkKeyBytes] (itself cached).
     *   • Owner-side album → fetch the album's BatchLinkDto, decrypt its nodeKey with the root key,
     *     and memoise in [parentKeyCache].
     * Returns null when the album link can't be fetched / decrypted (e.g. a shared-with-me album).
     */
    private suspend fun getParentKeyBytes(userId: UserId, parentLinkId: String, volumeId: String): ByteArray? {
        if (parentLinkId == shareService.photosRootLinkId()) {
            return shareService.getRootLinkKeyBytes(userId)
        }
        parentKeyCache[parentLinkId]?.let { return it }

        val rootKey = shareService.getRootLinkKeyBytes(userId) ?: return null
        val albumDetail = linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, listOf(parentLinkId))[parentLinkId]
            ?: run {
                Log.w(TAG, "getParentKeyBytes: album link $parentLinkId not in batch response")
                return null
            }
        val albumLink = albumDetail.link
        val albumNodeKey = albumLink.nodeKey ?: return null
        val albumNodePass = albumLink.nodePassphrase ?: return null
        val bytes = albumCryptoChain.decryptAlbumKey(
            nodeKeyArmored = albumNodeKey,
            nodePassphraseArmored = albumNodePass,
            parentKeyBytes = rootKey,
            contextHint = "video-duration-backfill albumLinkId=$parentLinkId",
        ) ?: return null
        parentKeyCache[parentLinkId] = bytes
        return bytes
    }

    private companion object {
        /** XAttr decrypts are light; a small pool clears a large library without disturbing scroll. */
        const val WORKER_COUNT = 4

        /** In-flight revision fetches the backfill owns, kept low so it never crowds foreground traffic. */
        const val FETCH_WORKER_COUNT = 3

        /** Per-fetch retry budget on a transient (429 / 5xx / network) revision read before giving up. */
        const val FETCH_MAX_ATTEMPTS = 3
        const val FETCH_RETRY_BASE_MS = 600L

        /** Gap between pages so a large library trickles instead of bursting page after page. */
        const val PAGE_DELAY_MS = 400L

        /** Rows pulled per page, bounds how many entities + their crypto material are resident. */
        const val PAGE = 200
    }
}
