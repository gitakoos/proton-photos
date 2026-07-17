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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import java.io.File
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
 * [urls] is a copy-on-write map: each [put] swaps in a new immutable instance with one changed entry.
 * A grid cell reads `urls[linkId]` for its own row, so the new map instance recomposes only the cells
 * whose value changed (per-cell cost stays O(1)). The DB write stays alongside every put so the URL
 * persists for [seed] on the next launch.
 */
@Singleton
class ThumbnailUrlStore @Inject constructor(
    private val photoListingDao: PhotoListingDao,
) {
    private val _urls = MutableStateFlow<Map<String, String>>(emptyMap())

    /** linkId → decrypted `file://` thumbnail URL. Cells read their own entry off this snapshot. */
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    /** Record the freshly-decrypted [url] for [linkId], swapping in a new map instance so the one cell
     *  reading this linkId recomposes. Copy-of-one, not a re-query; this is the whole point of the
     *  store. Idempotent: re-putting the same value is a cheap no-op emit-then-drop by StateFlow. */
    fun put(linkId: String, url: String) {
        _urls.update { current ->
            if (current[linkId] == url) current else current + (linkId to url)
        }
    }

    /** One-time prime from the DB so cells decrypted in a previous session paint immediately, without
     *  re-decrypting. Merges under any live [put]s that already landed (a decrypt can complete before
     *  the seed read returns), so a just-decrypted URL is never clobbered by the older seed row. */
    suspend fun seed(userId: UserId) {
        val rows = runCatching { photoListingDao.getThumbnailUrlSeed(userId.id) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        _urls.update { current ->
            val merged = HashMap<String, String>(current.size + rows.size)
            // Keep only URLs whose backing file is still present and non-empty. An OS "Clear cache"
            // wipes cacheDir/thumbnails/ without nulling the DB rows, so a stale file:// path would
            // otherwise seed a deleted file and leave that cell permanently blank (the scheduler
            // skips a re-decrypt while the row's URL is non-null). A dead path is dropped, so the
            // cell falls back to null and re-decrypts on scroll.
            for (row in rows) row.thumbnailUrl?.let { url ->
                if (thumbnailFileUsable(url)) merged[row.linkId] = url
            }
            // Live puts win over the seed snapshot.
            merged.putAll(current)
            merged
        }
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
        _urls.update { current ->
            if (linkIds.none { it in current }) current else current - linkIds.toSet()
        }
    }

    /** Drop everything on sign-out so a re-login (same or different account) starts clean. */
    fun clear() {
        _urls.value = emptyMap()
    }
}
