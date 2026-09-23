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

package eu.akoos.photos.data.repository.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.di.AppScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory linkId → decrypted-thumbnail `file://` URL map for the timeline.
 *
 * The timeline projection ([PhotoListingDao.observeOwnStreamLite]) deliberately dropped the
 * thumbnailUrl column. Room invalidation is table-level, so the per-linkId
 * [PhotoListingDao.updateThumbnailUrl] write a decrypt-completion makes re-runs that query either way;
 * dropping the column makes each re-emission byte-identical, so the distinctUntilChanged in
 * GetGalleryItemsUseCase drops it before the merge/group/regroup rebuild. With the column present each
 * such write was a distinct emission that forced a whole-library rebuild on every thumbnail landing
 * during a sustained scroll, the churn that tipped a large account into OutOfMemoryError. This store
 * carries the fresh URL to the affected cell instead, so a decrypt-completion repaints exactly one tile
 * and never touches the main item model.
 *
 * The entries live in one [ConcurrentHashMap] that [put] updates in place, so a decrypt costs one map
 * entry and never a copy of the whole map. [urls] carries a read-only view over those live entries,
 * and a change publishes a fresh view object: collectors and Compose state re-read because the new
 * view is a new value, while every view reads the same entries. Publishing is rate-limited by
 * [CoalescingPublisher]: the first change after a quiet spell goes out at once, and a burst of
 * decrypts collapses into at most one publish per [PUBLISH_WINDOW_MS]. A grid cell reads
 * `urls[linkId]` for its own row, so a publish rebinds only the cells whose value changed. The DB
 * write stays alongside every put so the URL persists for [seed] on the next launch.
 */
@Singleton
class ThumbnailUrlStore @Inject constructor(
    private val photoListingDao: PhotoListingDao,
    @AppScope appScope: CoroutineScope,
) {
    private val entries = ConcurrentHashMap<String, String>()

    private val _urls = MutableStateFlow<Map<String, String>>(LiveUrls(entries))

    /** linkId → decrypted `file://` thumbnail URL. Cells read their own entry off this view. */
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    private val publisher = CoalescingPublisher(appScope, PUBLISH_WINDOW_MS, ::publish)

    // Orders seed's check-then-insert against remove() and clear(). A put needs no lock: seed only
    // fills absent linkIds, so a live put always outranks the older seed row.
    private val lock = Any()
    private var generation = 0L

    /** Record the freshly-decrypted [url] for [linkId]. Readable through [urls] at once; the change
     *  signal that makes readers recompose is coalesced. Idempotent: re-putting the same value
     *  signals nothing. */
    fun put(linkId: String, url: String) {
        if (entries.put(linkId, url) != url) publisher.mark()
    }

    /** One-time prime from the DB so cells decrypted in a previous session paint immediately, without
     *  re-decrypting. Fills only linkIds no live [put] holds yet (a decrypt can complete before the seed
     *  read returns), so a just-decrypted URL is never clobbered by the older seed row. A [clear] that
     *  lands mid-seed ends it, so a signed-out account's rows are not written back. */
    suspend fun seed(userId: UserId) {
        val seedGeneration = synchronized(lock) { generation }
        val rows = runCatching { photoListingDao.getThumbnailUrlSeed(userId.id) }.getOrDefault(emptyList())
        var added = false
        for (row in rows) {
            val url = row.thumbnailUrl ?: continue
            if (entries.containsKey(row.linkId)) continue
            val stillCurrent = synchronized(lock) {
                if (generation != seedGeneration) return@synchronized false
                // Keep only URLs whose backing file is still present and non-empty. An OS "Clear cache"
                // wipes cacheDir/thumbnails/ without nulling the DB rows, so a stale file:// path would
                // otherwise seed a deleted file and leave that cell permanently blank (the scheduler
                // skips a re-decrypt while the row's URL is non-null). A dead path is dropped, so the
                // cell falls back to null and re-decrypts on scroll. Checked under the lock so an
                // eviction's remove() cannot slip between the check and the insert.
                if (thumbnailFileUsable(url) && entries.putIfAbsent(row.linkId, url) == null) added = true
                true
            }
            if (!stillCurrent) break
        }
        if (added) publish()
    }

    /** True when a `file://` [url] points at an existing, non-empty file. Non-file URLs are kept as-is
     *  (there is nothing local to validate). Same shape as ThumbnailHelpers.isCachedValid. */
    private fun thumbnailFileUsable(url: String): Boolean {
        if (!url.startsWith("file://")) return true
        val file = File(url.removePrefix("file://"))
        return file.exists() && file.length() > 0
    }

    /** Drop [linkIds] whose cached thumbnail files were just evicted, so those cells fall back to the
     *  placeholder and re-decrypt on next scroll instead of pointing at a deleted file. No-op when none
     *  of them are in the map. */
    fun remove(linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        var removed = false
        synchronized(lock) {
            for (linkId in linkIds) if (entries.remove(linkId) != null) removed = true
        }
        if (removed) publisher.mark()
    }

    /** Drop everything on sign-out so a re-login (same or different account) starts clean. Published
     *  at once rather than coalesced. */
    fun clear() {
        val hadEntries = synchronized(lock) {
            generation++
            val had = entries.isNotEmpty()
            entries.clear()
            had
        }
        if (hadEntries) publish()
    }

    private fun publish() {
        _urls.value = LiveUrls(entries)
    }

    companion object {
        /** Shortest spacing between two coalesced publishes of [urls]. */
        internal const val PUBLISH_WINDOW_MS = 150L
    }
}

/**
 * Read-only view over the store's live entries. Equality is identity: a new instance is the change
 * signal [ThumbnailUrlStore.urls] publishes, and two views over the same live map always hold the same
 * entries, so a content compare would swallow every change.
 */
private class LiveUrls(entries: Map<String, String>) : Map<String, String> by entries

/**
 * Rate-limits a change signal to at most one [publish] per [windowMs]. The first [mark] after a quiet
 * spell publishes at once and opens a window; marks inside it share one publish when it closes, and a
 * new window follows for as long as changes keep arriving. Every mark is followed by a publish that
 * sees it, so the last change of a burst always lands.
 */
internal class CoalescingPublisher(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val publish: () -> Unit,
) {
    // A change is waiting for a publish. Every write to it is a read-modify-write, so a flush that
    // reads it is ordered after every mark before it and its publish sees their changes.
    private val owed = AtomicBoolean(false)

    // A publish went out less than windowMs ago and [holdWindow] is running to close the window.
    private val windowOpen = AtomicBoolean(false)

    fun mark() {
        owed.getAndSet(true)
        if (windowOpen.compareAndSet(false, true)) {
            flush()
            scope.launch { holdWindow() }
        }
    }

    private suspend fun holdWindow() {
        while (true) {
            delay(windowMs)
            if (owed.get()) {
                flush()
                continue
            }
            windowOpen.set(false)
            // A mark that landed between the check and the reset still found the window open and left
            // its change to this loop: publish it here rather than lose it.
            if (!owed.get() || !windowOpen.compareAndSet(false, true)) return
            flush()
        }
    }

    private fun flush() {
        owed.getAndSet(false)
        publish()
    }
}
