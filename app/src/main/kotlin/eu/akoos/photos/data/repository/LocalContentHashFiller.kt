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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One local file worth content-hashing for the exact-duplicate finder, because it shares its byte
 *  size with at least one other local file. [freshness] is "<dateModified>_<sizeBytes>" so a file
 *  replaced in place re-hashes. Pure data, so the size pre-filter can be unit-tested off-device. */
data class LocalHashCandidate(val uri: String, val freshness: String)

/**
 * The device-only local files worth content-hashing for the exact-duplicate finder: only those that
 * share a byte size with at least one other local file. Two files can be byte-identical ONLY if their
 * sizes match, and MediaStore reports the size for free, so a file with a unique size cannot have an
 * exact duplicate and is skipped. This is what keeps the finder from reading every file in a large
 * library. Pure and allocation-light for the unit test.
 */
fun sameSizeLocalCandidates(items: List<GalleryItem>): List<LocalHashCandidate> {
    val locals = items.mapNotNull { item ->
        (item as? GalleryItem.LocalOnly)?.local?.takeIf { it.sizeBytes > 0L }
    }
    return locals.groupBy { it.sizeBytes }
        .values
        .filter { it.size >= 2 }
        .flatten()
        .map { LocalHashCandidate(it.uri, "${it.dateModified}_${it.sizeBytes}") }
}

/**
 * Fills the exact-duplicate finder's local content-hash map on device, with no Proton account and no
 * dependence on the backup pipeline (which is where the cloud/synced content hashes come from, so a
 * device-only or signed-out photo had none and its exact copies were never found).
 *
 * Given the live gallery items it size-pre-filters to the same-size local candidates (see
 * [sameSizeLocalCandidates]), streams a SHA-1 for each one whose stored hash is missing or stale, and
 * publishes uri -> SHA-1 for [eu.akoos.photos.presentation.duplicates.DuplicateFinderViewModel] to
 * group. Because only same-size files are ever hashed, a large library costs a handful of full-file
 * reads rather than one per photo, and the SHA-1 is streamed so memory stays flat regardless of file
 * size. The map is held in memory: the candidate set is small, so rebuilding it on the next launch is
 * cheap, and a stale uri that is no longer a candidate is simply never looked up.
 */
@Singleton
class LocalContentHashFiller @Inject constructor(
    private val localMediaRepo: LocalMediaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bound on in-flight file reads; a handful keeps the hashing off the scroll path. */
    private val semaphore = Semaphore(WORKER_COUNT)

    private data class Cached(val sha1: String, val freshness: String)

    /** uri -> freshly hashed content hash, kept across screen opens within the app session so a
     *  reopened finder does not re-read the files. */
    private val cache = ConcurrentHashMap<String, Cached>()

    private val _hashes = MutableStateFlow<Map<String, String>>(emptyMap())
    /** uri -> SHA-1 for the current same-size candidate set, filled progressively as hashes land. */
    val hashes: StateFlow<Map<String, String>> = _hashes.asStateFlow()

    private var currentPass: Job? = null

    /**
     * Hash the same-size local candidates of [items] that are not already fresh, publishing each as it
     * lands so the finder's groups fill in. Cheap to call on every library change: an unchanged
     * candidate is served from [cache], and a new call supersedes the previous pass so a fast-moving
     * library does not stack overlapping walks.
     */
    fun request(items: List<GalleryItem>) {
        val candidates = sameSizeLocalCandidates(items)
        currentPass?.cancel()
        currentPass = scope.launch {
            candidates.forEach { c ->
                val cached = cache[c.uri]
                if (cached != null && cached.freshness == c.freshness) {
                    if (_hashes.value[c.uri] != cached.sha1) _hashes.update { it + (c.uri to cached.sha1) }
                } else {
                    launch {
                        semaphore.withPermit {
                            // sha1() streams the file and returns null on any read error while
                            // rethrowing cancellation, so a cancelled pass stops cleanly.
                            val sha1 = localMediaRepo.sha1(c.uri) ?: return@withPermit
                            cache[c.uri] = Cached(sha1, c.freshness)
                            _hashes.update { it + (c.uri to sha1) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val WORKER_COUNT = 3
    }
}
