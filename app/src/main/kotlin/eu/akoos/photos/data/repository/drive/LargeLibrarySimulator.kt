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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "LargeLibrarySim"

/**
 * Sentinel + on-disk layout shared by [LargeLibrarySimulator] and the DEBUG-only branch in
 * [ThumbnailHelpers.downloadAndDecryptBinary]. Kept out of the simulator class so the helper can
 * reference it without depending on the (debug-only) simulator graph.
 *
 * A synthetic row's [PhotoListingEntity.serverThumbnailUrl] is `simlocal://<seedIndex>`. The decrypt
 * path recognises that scheme and reads the seed's already-fetched ENCRYPTED blob off disk instead
 * of doing a CDN GET — so the real session-key/node-key decrypt still runs, with zero network.
 */
object LargeLibrarySim {
    /** linkId prefix for every synthetic row. `Clear simulation` deletes by this prefix. */
    const val LINK_ID_PREFIX = "SIMULATED_"

    /** Thumbnail-source scheme that resolves to a seed's local encrypted blob. */
    const val LOCAL_SCHEME = "simlocal://"

    /** Stable per-seed encrypted-blob file: `cache/sim_blobs/seed_<index>.bin`. */
    fun seedBlobFile(context: Context, seedIndex: Int): File =
        File(File(context.cacheDir, "sim_blobs").also { it.mkdirs() }, "seed_$seedIndex.bin")

    /** Parse the seed index out of a `simlocal://<index>` url, or null if it isn't one. */
    fun seedIndexOf(url: String?): Int? =
        url?.removePrefix(LOCAL_SCHEME)?.takeIf { url.startsWith(LOCAL_SCHEME) }?.toIntOrNull()
}

/**
 * DEBUG-only large-library simulator. Generates N synthetic [PhotoListingEntity] rows that run the
 * REAL lazy-thumbnail decrypt pipeline (`:crypto`, the adaptive cache, eviction, the grid treadmill)
 * with ZERO Proton CDN traffic, so the ~21k-photo experience can be exercised on demand.
 *
 * How it stays faithful: each synthetic row CLONES a real already-synced seed row's crypto material
 * verbatim (encNodeKey, encNodePassphrase, contentKeyPacket, parentLinkId, shareId, volumeId, userId).
 * Only the identity/spread fields change — linkId becomes a unique `SIMULATED_<i>`, captureTime is
 * spread across 2002→now, mimeType alternates image/video seeds, and serverThumbnailUrl becomes a
 * `simlocal://<seedIndex>` sentinel that the decrypt path resolves to the seed's local encrypted
 * blob. Decrypting a clone with the cloned keys therefore succeeds exactly like its seed.
 *
 * Entirely inert unless invoked from the BuildConfig.DEBUG-gated settings entry — nothing here runs
 * on the normal listing/refresh path.
 */
@Singleton
class LargeLibrarySimulator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoListingDao: PhotoListingDao,
    private val cdnBlockFetcher: CdnBlockFetcher,
    private val thumbnailDecryptScheduler: ThumbnailDecryptScheduler,
) {

    /** Outcome of a [populate] run, surfaced to the debug settings UI. */
    data class Result(val inserted: Int, val seedCount: Int, val message: String)

    /**
     * Generate [count] synthetic rows from up to [MAX_SEEDS] real seed rows.
     *
     * Steps: pick seeds with full crypto material + a server thumbnail → cache each seed's encrypted
     * blob to a stable local file (one CDN GET per seed, reused for all clones) → clone in batches.
     */
    suspend fun populate(count: Int): Result = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext Result(0, 0, "Count must be > 0")
        val userId = accountManager.getPrimaryUserId().first()
            ?: return@withContext Result(0, 0, "No signed-in account")

        val seeds = pickSeeds(userId)
        if (seeds.isEmpty()) {
            return@withContext Result(0, 0, "No synced photos with a thumbnail to seed from")
        }

        // Ensure each seed's ENCRYPTED thumbnail blob is on disk (fetch once via the normal CDN path).
        // A seed whose blob can't be fetched is dropped so its clones don't render blanks.
        val usableSeeds = seeds.filterIndexed { index, seed -> ensureSeedBlob(userId, seed, index) }
        if (usableSeeds.isEmpty()) {
            return@withContext Result(0, 0, "Could not cache any seed thumbnail blob")
        }
        // Remap to dense indices that line up with the blob files written above.
        val seedBlobIndex = seeds.withIndex().filter { it.value in usableSeeds }.map { it.index }

        val nowMs = System.currentTimeMillis()
        val startMs = epochMsForYear(2002)
        val span = (nowMs - startMs).coerceAtLeast(1L)

        var inserted = 0
        val batch = ArrayList<PhotoListingEntity>(BATCH_SIZE)
        for (i in 0 until count) {
            val poolPos = i % usableSeeds.size
            val seed = usableSeeds[poolPos]
            val seedIndex = seedBlobIndex[poolPos]
            // Spread captureTime evenly across the window, newest-ish last, with light jitter so the
            // day grouping isn't perfectly uniform.
            val t = startMs + (span * i / count) + (i % 86_400_000L)
            // ~12% videos: clone a video seed's mime if available, else tag the row as video.
            val isVideo = i % 8 == 0
            val mime = when {
                isVideo && seed.mimeType.startsWith("video/") -> seed.mimeType
                isVideo -> "video/mp4"
                seed.mimeType.startsWith("video/") -> "image/jpeg"
                else -> seed.mimeType.ifEmpty { "image/jpeg" }
            }
            batch += seed.copy(
                linkId = "${LargeLibrarySim.LINK_ID_PREFIX}$i",
                // captureTime is stored in SECONDS (Proton wire format), not millis.
                captureTime = t.coerceAtMost(nowMs) / 1000L,
                mimeType = mime,
                // Force the lazy path: null thumbnailUrl + sentinel source. The decrypt path reads
                // the seed's local blob and decrypts with the cloned keys below.
                thumbnailUrl = null,
                serverThumbnailUrl = "${LargeLibrarySim.LOCAL_SCHEME}$seedIndex",
                // Crypto material is carried over verbatim by copy(): encNodeKey, encNodePassphrase,
                // contentKeyPacket, parentLinkId, shareId, volumeId, userId.
            )
            if (batch.size >= BATCH_SIZE) {
                photoListingDao.upsertAll(batch)
                inserted += batch.size
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            photoListingDao.upsertAll(batch)
            inserted += batch.size
        }
        Log.d(TAG, "populate: inserted $inserted synthetic rows from ${usableSeeds.size} seed(s)")
        Result(inserted, usableSeeds.size, "Inserted $inserted photos from ${usableSeeds.size} seed(s)")
    }

    /**
     * Remove every synthetic row + its decrypted thumbnail and the cached seed blobs. The crypto
     * material lived only on the synthetic rows, so this leaves the real library untouched.
     */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        val userId = accountManager.getPrimaryUserId().first()
        val ids = (userId?.let { photoListingDao.getAllLinkIds(it.id) } ?: emptyList())
            .filter { it.startsWith(LargeLibrarySim.LINK_ID_PREFIX) }
        if (ids.isNotEmpty()) {
            ids.chunked(BATCH_SIZE).forEach { photoListingDao.deleteByLinkIds(it) }
            // Drop the decrypted thumbnails the scheduler wrote for these rows.
            val thumbDir = File(context.cacheDir, "thumbnails")
            ids.forEach { File(thumbDir, "thumb_$it.jpg").delete() }
        }
        thumbnailDecryptScheduler.clear()
        File(context.cacheDir, "sim_blobs").deleteRecursively()
        Log.d(TAG, "clear: removed ${ids.size} synthetic rows")
        ids.size
    }

    /** Up to [MAX_SEEDS] real own-stream rows that carry the full decrypt material + a CDN thumbnail. */
    private suspend fun pickSeeds(userId: UserId): List<PhotoListingEntity> =
        photoListingDao.observeOwnStream(userId.id).first()
            .filter { row ->
                !row.linkId.startsWith(LargeLibrarySim.LINK_ID_PREFIX) &&
                    row.serverThumbnailUrl?.startsWith("http") == true &&
                    row.contentKeyPacket != null &&
                    row.encNodeKey != null &&
                    row.encNodePassphrase != null &&
                    row.parentLinkId != null
            }
            .take(MAX_SEEDS)

    /** Fetch + cache a seed's encrypted thumbnail blob once. True when the blob is on disk.
     *  The seed's STORED CDN url is a signed url that may have expired (HTTP 404), so refresh it
     *  first — the same on-demand refresh the lazy decrypt does for a stale url. */
    private suspend fun ensureSeedBlob(userId: UserId, seed: PhotoListingEntity, seedIndex: Int): Boolean {
        val out = LargeLibrarySim.seedBlobFile(context, seedIndex)
        if (out.exists() && out.length() > 0) return true
        val fresh = runCatching {
            thumbnailDecryptScheduler.fetchFreshThumbnailInfo(userId, seed.linkId, seed.volumeId)
        }.getOrNull()
        val url = fresh?.bareUrl ?: seed.serverThumbnailUrl ?: return false
        val token = fresh?.token ?: seed.serverThumbnailToken
        return runCatching {
            val bytes = cdnBlockFetcher.fetchBlock(url = url, token = token, maxAttempts = 3)
            if (bytes.isEmpty()) return false
            out.writeBytes(bytes)
            true
        }.getOrElse { e ->
            Log.w(TAG, "ensureSeedBlob: seed $seedIndex fetch failed: ${e.message}")
            false
        }
    }

    private fun epochMsForYear(year: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, Calendar.JANUARY, 1)
        }.timeInMillis

    private companion object {
        /** Seed pool size — enough variety in capture metadata while keeping the one-time CDN
         *  fetch (one per seed) tiny. */
        const val MAX_SEEDS = 8

        /** DB upsert batch size for the bulk insert. */
        const val BATCH_SIZE = 500
    }
}
