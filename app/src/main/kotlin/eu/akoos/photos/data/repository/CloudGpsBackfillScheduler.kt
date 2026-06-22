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

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.crypto.parsePhotoLocation
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.AlbumCryptoChain
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.util.isTransientApiError
import eu.akoos.photos.util.retryWithBackoff
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudGpsBackfill"

/**
 * Fills the persisted [PhotoLocationEntity] table with the GPS fix of every CLOUD photo, so the
 * map plots synced photos the same way it plots on-device ones. Piece 2 stored each photo's
 * encrypted XAttr in `photo_listing.encXAttr` at listing time; this walks every un-checked row
 * ([PhotoListingDao.getUngeocoded]), decrypts the XAttr off the read path, recovers the Location
 * block via [parsePhotoLocation], and upserts the coordinates keyed by the photo's `linkId` (a
 * CloudOnly map item resolves by that id).
 *
 * The link-metadata endpoints omit the revision XAttr, so for any row without a cached one the
 * armored XAttr is fetched per photo from the revision endpoint via
 * [LinkDetailHelpers.fetchRevisionXAttr] — the same source the full-res download reads — while
 * newly-listed rows that already cached an XAttr skip the fetch.
 *
 * Resolving the parent key mirrors [eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler]
 * — the photo's nodeKey was encrypted to its parent link's key (the photos root, or an album).
 * That scheduler's `getParentKeyBytes` is private and seeded with a shared-album context the
 * thumbnail path owns, so rather than refactor a libgojni-sensitive file this replicates the two
 * owner-side branches against the same injected services, with a local parent-key cache so
 * successive photos in one parent skip the second-tier decrypt. A photo whose parent key can't be
 * resolved here (e.g. a shared-with-me album) is simply skipped, like any other per-row failure.
 *
 * The walk is resumable and idempotent: a row is flagged via [PhotoListingDao.markGpsChecked]
 * only once its revision was actually READ (whether or not it carried GPS), so a transient fetch
 * failure (offline / rate-limited / a brief 5xx) leaves the row at `gpsChecked = 0` for a later run
 * to retry instead of permanently skipping it. The walk stops a pass early when a page resolves zero
 * new revisions, so a sustained outage can't spin the loop. The revision fetches and the decrypts are
 * each paced by their own small [Semaphore] so the backfill trickles a large library without flooding
 * the Drive API or starving foreground scroll, with a brief pause between pages; per-photo fetches go
 * through the shared 429 / 5xx-aware [retryWithBackoff] so a rate-limit backs off rather than spamming.
 * A single [backfilling] guard collapses overlapping triggers so concurrent calls don't double-run.
 */
@Singleton
class CloudGpsBackfillScheduler @Inject constructor(
    private val cryptoServiceClient: CryptoServiceClient,
    private val photoListingDao: PhotoListingDao,
    private val photoLocationDao: PhotoLocationDao,
    private val shareService: PhotosShareService,
    private val linkDetailHelpers: LinkDetailHelpers,
    private val albumCryptoChain: AlbumCryptoChain,
) {
    /** Concurrency bound on in-flight XAttr decrypts — a handful keeps JNI / GC pressure low. */
    private val semaphore = Semaphore(WORKER_COUNT)

    /**
     * Tighter bound on in-flight revision fetches. Each call also rides the shared Drive permit pool
     * inside [LinkDetailHelpers.fetchRevisionXAttrOrThrow], but capping the backfill itself here keeps
     * only a couple of its requests outstanding so it never crowds out gallery / thumbnail traffic.
     */
    private val fetchSemaphore = Semaphore(FETCH_WORKER_COUNT)

    /** One walk at a time — a second trigger while one is running is a no-op, not a double pass. */
    private val backfilling = AtomicBoolean(false)

    /** parentLinkId → decrypted parent key bytes, so photos sharing a parent skip the re-decrypt. */
    private val parentKeyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Decrypt + persist the GPS fix for every cloud photo not yet checked for [userId]. Walks the
     * un-geocoded XAttr rows in bounded pages until none remain, fetches paced by [fetchSemaphore]
     * and decrypts by [semaphore], with a short [PAGE_DELAY_MS] gap between pages. Stops a pass early
     * when a page resolves no new revisions (a sustained outage), leaving those rows for a later run.
     * A no-op once every row has been processed (nothing left with `gpsChecked = 0`), and
     * self-collapsing across overlapping calls, so the screen can fire it freely on entry.
     */
    suspend fun backfillAll(userId: UserId) {
        if (!backfilling.compareAndSet(false, true)) return
        try {
            while (true) {
                val batch = runCatching { photoListingDao.getUngeocoded(userId.id, PAGE) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "query failed: ${e.message}"); break
                    }
                if (batch.isEmpty()) break

                val resolved = resolveXAttrs(userId, batch)

                // A page whose every fetch failed (offline / rate-limited / endpoint down) resolves
                // no NEW revision: nothing to mark, so marking-as-checked can't advance the walk.
                // Stop this pass rather than spin — the rows stay gpsChecked=0 and a later run retries
                // from the same spot. (Cached rows count as resolved, so a page that's all cache still
                // makes progress.)
                if (resolved.ids.isEmpty()) {
                    Log.d(TAG, "page resolved 0 revisions — pausing walk, will retry on a later run")
                    break
                }

                val located = ConcurrentHashMap.newKeySet<PhotoLocationEntity>()
                coroutineScope {
                    batch.forEach { row ->
                        if (row.linkId !in resolved.ids) return@forEach
                        launch {
                            try {
                                semaphore.withPermit {
                                    locate(userId, row, resolved.byLinkId[row.linkId])?.let { located.add(it) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.w(TAG, "gps decrypt ${row.linkId} failed: ${e.message}")
                            }
                        }
                    }
                }

                Log.d(TAG, "page: walked=${batch.size} xattrResolved=${resolved.ids.size} located=${located.size}")
                if (located.isNotEmpty()) {
                    runCatching { photoLocationDao.upsert(located.toList()) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "upsert ${located.size} location(s) failed: ${it.message}")
                        }
                }
                // Flag only rows whose revision was actually read (cached or freshly fetched),
                // including no-GPS ones — those advance the walk and never need reprocessing. Rows
                // whose fetch FAILED are absent from resolved.ids, so they keep gpsChecked=0 and a
                // later run retries them.
                runCatching { photoListingDao.markGpsChecked(resolved.ids.toList()) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "markGpsChecked failed: ${it.message}")
                    }

                if (batch.size < PAGE) break

                // Trickle a large library: a short gap between pages keeps the backfill gentle on the
                // Drive API and off the foreground's back instead of bursting page after page.
                delay(PAGE_DELAY_MS)
            }
        } finally {
            backfilling.set(false)
        }
    }

    /**
     * The outcome of resolving a page's XAttrs: [byLinkId] holds every armored XAttr we actually have
     * (cached or fetched), while [ids] is the set of linkIds whose revision was successfully READ —
     * which is a superset of [byLinkId]'s keys, since a revision can be read yet carry no XAttr. Only
     * [ids] advances the walk; rows missing from it had their fetch fail and are retried on a later run.
     */
    private class ResolvedXAttrs(
        val byLinkId: Map<String, String>,
        val ids: Set<String>,
    )

    /**
     * Resolve one page's XAttrs. Rows with a cached [PhotoListingEntity.encXAttr] contribute it
     * directly (no network) and count as resolved. The rest have their XAttr fetched per photo from
     * the revision endpoint via [LinkDetailHelpers.fetchRevisionXAttrOrThrow] — the link-metadata
     * endpoints omit it, only the revision carries it. Each fetch is gated through [fetchSemaphore] so
     * only a couple are ever in flight (it also rides the network layer's own permit pool underneath),
     * and wrapped in the shared 429 / 5xx-aware [retryWithBackoff] so a transient blip backs off and
     * retries rather than spamming or surfacing. A fetch that still fails after its retries leaves the
     * row out of [ResolvedXAttrs.ids] so a later run retries it; a successful fetch with no XAttr is
     * resolved (in [ids]) but absent from [byLinkId], so [locate] simply yields no GPS for it.
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
                        // Fetch failed even after retries — leave the row unresolved so a later run
                        // retries it. A sustained rate-limit shows up as a page of these, which the
                        // zero-progress guard in backfillAll turns into a clean pause.
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

    /** Decrypt one row's XAttr and return its location, or null when it carries no GPS / can't be
     *  resolved. [armoredXAttr] is the row's cached XAttr or, for pre-encXAttr rows, the value
     *  fetched in [resolveXAttrs]. The parent key resolution mirrors the thumbnail scheduler's
     *  owner-side branches. */
    private suspend fun locate(
        userId: UserId,
        row: PhotoListingEntity,
        armoredXAttr: String?,
    ): PhotoLocationEntity? {
        val encXAttr = armoredXAttr ?: return null
        val encNodeKey = row.encNodeKey ?: return null
        val encNodePass = row.encNodePassphrase ?: return null
        val parentLinkId = row.parentLinkId ?: return null
        val parentKey = getParentKeyBytes(userId, parentLinkId, row.volumeId) ?: return null
        val nodeKeyBytes = cryptoServiceClient.decryptNodeKey(encNodeKey, encNodePass, parentKey)
        val json = cryptoServiceClient.decryptXAttr(encXAttr, nodeKeyBytes) ?: return null
        val (lat, lon) = parsePhotoLocation(json) ?: return null
        return PhotoLocationEntity(id = row.linkId, userId = userId.id, latitude = lat, longitude = lon)
    }

    /**
     * Decrypted node-key bytes for [parentLinkId], replicating the owner-side resolution in
     * [eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler.getParentKeyBytes]:
     *   • Photos root link → [PhotosShareService.getRootLinkKeyBytes] (itself cached).
     *   • Owner-side album → fetch the album's BatchLinkDto, decrypt its nodeKey with the root key,
     *     and memoise in [parentKeyCache].
     * Returns null when the album link can't be fetched / decrypted (e.g. a shared-with-me album,
     * which the thumbnail path resolves through a context map this backfill is not seeded with).
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
            contextHint = "cloud-gps-backfill albumLinkId=$parentLinkId",
        ) ?: return null
        parentKeyCache[parentLinkId] = bytes
        return bytes
    }

    private companion object {
        /** XAttr decrypts are light; a small pool clears a large library without disturbing scroll. */
        const val WORKER_COUNT = 4

        /** In-flight revision fetches the backfill owns — kept low so it never crowds foreground traffic. */
        const val FETCH_WORKER_COUNT = 3

        /** Per-fetch retry budget on a transient (429 / 5xx / network) revision read before giving up. */
        const val FETCH_MAX_ATTEMPTS = 3
        const val FETCH_RETRY_BASE_MS = 600L

        /** Gap between pages so a large library trickles instead of bursting page after page. */
        const val PAGE_DELAY_MS = 400L

        /** Rows pulled per page — bounds how many entities + their crypto material are resident. */
        const val PAGE = 200
    }
}
