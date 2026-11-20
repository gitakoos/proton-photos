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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import eu.akoos.photos.domain.entity.GalleryItem
import kotlinx.coroutines.flow.distinctUntilChanged

/** Asks for and cancels the decrypt of a cloud photo's grid thumbnail, by its linkId. */
class GridThumbnailRequester(val request: (String) -> Unit, val cancel: (String) -> Unit)

/**
 * The thumbnail request path of the timeline grid, for the grids that have no view model wired to it (a person's
 * page, Find more photos, a place, a day, search results, a device folder). Provided at the nav root; a grid
 * composed outside it asks for nothing.
 */
val LocalGridThumbnailRequester: ProvidableCompositionLocal<GridThumbnailRequester> =
    staticCompositionLocalOf { GridThumbnailRequester(request = {}, cancel = {}) }

/** Cells past the last visible one whose thumbnails are asked for too, so a slow scroll finds them ready. */
internal const val THUMBNAIL_LEAD_CELLS = 12

/**
 * The linkId whose thumbnail [item]'s cell still needs decrypted: a cloud row with no URL in the store
 * ([storeUrls]) and none of its own. A device photo, and a cloud row already decrypted, need nothing.
 */
internal fun pendingCloudThumbnailLinkId(item: GalleryItem, storeUrls: Map<String, String>): String? = when (item) {
    is GalleryItem.CloudOnly -> item.cloud.linkId.takeIf { storeUrls[it] == null && item.cloud.thumbnailUrl == null }
    is GalleryItem.Synced -> item.cloud.linkId.takeIf { storeUrls[it] == null && item.cloud.thumbnailUrl == null }
    is GalleryItem.LocalOnly -> null
}

/**
 * The linkIds to ask for, in grid order, for the visible items [firstIdx]..[lastIdx] of [items] plus
 * [THUMBNAIL_LEAD_CELLS] after them. Nothing when no photo cell is visible (both null).
 */
internal fun thumbnailRequestWindow(
    items: List<GalleryItem>,
    firstIdx: Int?,
    lastIdx: Int?,
    pendingLinkIdOf: (GalleryItem) -> String?,
): LinkedHashSet<String> {
    val wanted = LinkedHashSet<String>()
    if (firstIdx == null || lastIdx == null) return wanted
    val to = (lastIdx + 1 + THUMBNAIL_LEAD_CELLS).coerceAtMost(items.size)
    for (i in firstIdx.coerceAtLeast(0) until to) pendingLinkIdOf(items[i])?.let { wanted += it }
    return wanted
}

/**
 * Asks for the thumbnails of the cloud cells in view, in view order plus a small lead, and cancels the ones that
 * scrolled out, for as long as the calling effect runs. [visibleKeys] reads the grid's visible cell keys in a
 * snapshot; [indexOfKey] maps a photo cell's key to its position in [items] and gives null for any other row (a
 * header, a banner), which is passed over.
 */
internal suspend fun visibleRangeDecrypt(
    items: List<GalleryItem>,
    indexOfKey: (Any) -> Int?,
    pendingLinkIdOf: (GalleryItem) -> String?,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
    visibleKeys: () -> List<Any>,
) {
    var requested = emptySet<String>()
    snapshotFlow { visibleKeys() }.distinctUntilChanged().collect { keys ->
        val firstIdx = keys.firstNotNullOfOrNull(indexOfKey)
        val lastIdx = keys.asReversed().firstNotNullOfOrNull(indexOfKey)
        val wanted = thumbnailRequestWindow(items, firstIdx, lastIdx, pendingLinkIdOf)
        // Asked again on every move; the scheduler drops a request it already holds.
        wanted.forEach(onRequestThumbnail)
        requested.forEach { if (it !in wanted) onCancelThumbnail(it) }
        requested = wanted
    }
}

/**
 * Drives the thumbnail decrypts of a [LazyVerticalGrid][androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * of [items] whose photo cells are keyed by [keyOf], the way the timeline does, through [LocalGridThumbnailRequester].
 */
@Composable
fun RequestVisibleThumbnails(gridState: LazyGridState, items: List<GalleryItem>, keyOf: (GalleryItem) -> Any) {
    val keys = remember(items) { items.map(keyOf) }
    RequestVisibleThumbnails(gridState, items, keys)
}

/** As above, for a grid whose cell keys are not a function of the photo alone: [keys] holds each item's cell key. */
@Composable
fun RequestVisibleThumbnails(gridState: LazyGridState, items: List<GalleryItem>, keys: List<Any>) {
    val requester = LocalGridThumbnailRequester.current
    val urls = LocalThumbnailUrls.current
    val indexByKey = remember(keys) { HashMap<Any, Int>(keys.size * 2).also { m -> keys.forEachIndexed { i, k -> m[k] = i } } }
    LaunchedEffect(gridState, items, indexByKey, requester) {
        visibleRangeDecrypt(
            items = items,
            indexOfKey = { indexByKey[it] },
            pendingLinkIdOf = { pendingCloudThumbnailLinkId(it, urls.value) },
            onRequestThumbnail = requester.request,
            onCancelThumbnail = requester.cancel,
            visibleKeys = { gridState.layoutInfo.visibleItemsInfo.map { it.key } },
        )
    }
}
