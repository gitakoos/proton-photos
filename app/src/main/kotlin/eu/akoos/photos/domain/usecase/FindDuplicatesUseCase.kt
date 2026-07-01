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

package eu.akoos.photos.domain.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.akoos.photos.domain.entity.GalleryItem
import javax.inject.Inject

/**
 * Phase 1 exact-duplicate finder. Groups photos that share an identical stored content hash so the
 * caller can offer to free the redundant copies. It never hashes anything new — it reads only the
 * hashes the app already persisted, so coverage depends on what was already indexed (see below).
 *
 * Three safety properties are guaranteed HERE, at the grouping boundary, so the screen and the
 * delete code downstream can only ever act on proven duplicates:
 *
 *  - Only items with a NON-EMPTY hash are grouped. A null/blank hash is never a key, so two photos
 *    whose hash is simply unknown are never declared duplicates of each other.
 *  - Only groups of size > 1 are returned, so a unique photo is never offered for deletion.
 *  - A [GalleryItem.Synced] photo is ONE photo (its device + cloud copies are already merged into a
 *    single item upstream), so it is grouped like any cloud-backed photo, by its ContentHash. Two
 *    DIFFERENT synced photos that share a hash are real duplicates of each other. A bare local file
 *    and a cloud file still stay in SEPARATE buckets: an unpaired local+cloud pair may be the same
 *    photo rather than two copies, so they are never collapsed into one group.
 *
 * Device coverage caveat: a [GalleryItem.LocalOnly] is grouped only when a stored localHash exists
 * for its URI ([localHashes]). Phase 1 runs no new hashing scan, so an un-indexed local file simply
 * isn't grouped (it is omitted, never guessed). Cloud coverage is complete because every cloud photo
 * carries its ContentHash — which is the primary value here (it surfaces redundant uploads).
 */
class FindDuplicatesUseCase @Inject constructor() {

    enum class GroupType { DEVICE, CLOUD }

    /** A set of two or more photos proven identical by an equal stored content hash. */
    data class DuplicateGroup(
        val type: GroupType,
        val items: List<GalleryItem>,
    )

    data class Result(
        val deviceGroups: List<DuplicateGroup>,
        val cloudGroups: List<DuplicateGroup>,
    )

    /**
     * @param items the merged gallery list from [GetGalleryItemsUseCase].
     * @param localHashes localUri → stored SHA-1, used to group device-only files by content.
     */
    suspend operator fun invoke(
        items: List<GalleryItem>,
        localHashes: Map<String, String>,
    ): Result = withContext(Dispatchers.Default) {
        // CLOUD: cloud-only photos keyed by their non-empty ContentHash. Synced items are skipped
        // by the `is CloudOnly` filter — they carry a cloud hash but are not redundant copies.
        val cloudGroups = items
            .mapNotNull { item ->
                // Cloud-backed photos: CloudOnly AND Synced both carry a cloud ContentHash. A Synced
                // photo is ONE photo (its device + cloud copies are merged upstream), so two DIFFERENT
                // synced photos that share a hash are real duplicates of each other and belong here.
                val hash = when (item) {
                    is GalleryItem.CloudOnly -> item.cloud.contentHash
                    is GalleryItem.Synced -> item.cloud.contentHash
                    else -> null
                }?.takeIf { it.isNotEmpty() }
                hash?.let { it to item }
            }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { it.size > 1 }
            .map { DuplicateGroup(GroupType.CLOUD, it.sortedBy { item -> item.captureTimeMs }) }

        // DEVICE: local-only photos keyed by their stored localHash. A file with no stored hash
        // contributes no key and is therefore never grouped (the coverage caveat above).
        val deviceGroups = items
            .filterIsInstance<GalleryItem.LocalOnly>()
            .mapNotNull { item ->
                localHashes[item.local.uri]?.takeIf { it.isNotEmpty() }?.let { hash -> hash to item }
            }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { it.size > 1 }
            .map { DuplicateGroup(GroupType.DEVICE, it.sortedBy { item -> item.captureTimeMs }) }

        Result(deviceGroups = deviceGroups, cloudGroups = cloudGroups)
    }
}
