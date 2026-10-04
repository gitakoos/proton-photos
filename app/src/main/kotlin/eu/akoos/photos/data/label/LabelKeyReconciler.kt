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

package eu.akoos.photos.data.label

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.util.LabelNormalizer

/**
 * Where a photo's labels can sit. A label is written under the photo's stableId, which for a backed-up
 * photo is its Drive linkId; one put on the device photo before the backup sits under its content URI
 * until a re-key moves it. Upload re-keys at once, but a photo backed up on another device, or paired
 * by a reconcile rather than by this app's own upload, reaches the Synced state with no upload here to
 * move its labels. So every lookup of a Synced item reads both keys, and [pendingMoves] names the rows
 * still under a URI so a caller holding the items can move them for good.
 */
object LabelKeyReconciler {

    /** The keys [item]'s labels may sit under, the stableId first. */
    fun keysFor(item: GalleryItem): List<String> = when (item) {
        is GalleryItem.Synced ->
            if (item.local.uri == item.cloud.linkId) listOf(item.cloud.linkId)
            else listOf(item.cloud.linkId, item.local.uri)
        else -> listOf(item.stableId)
    }

    /**
     * [item]'s labels from a key-to-labels map: those under its stableId, then for a Synced item any
     * under its device URI that are not there yet, up to [LabelNormalizer.MAX_LABELS].
     */
    fun labelsFor(item: GalleryItem, byKey: Map<String, List<String>>): List<String> {
        val keys = keysFor(item)
        val primary = byKey[keys.first()].orEmpty()
        if (keys.size == 1) return primary
        val secondary = byKey[keys[1]].orEmpty()
        if (secondary.isEmpty()) return primary
        return LabelNormalizer.merge(primary, secondary).labels
    }

    /** (device URI, linkId) for each Synced item in [items] whose URI still holds labels in [labelledKeys]. */
    fun pendingMoves(items: Iterable<GalleryItem>, labelledKeys: Set<String>): List<Pair<String, String>> =
        items.mapNotNull { item ->
            if (item !is GalleryItem.Synced) return@mapNotNull null
            val uri = item.local.uri
            if (uri == item.cloud.linkId || uri !in labelledKeys) null else uri to item.cloud.linkId
        }
}
